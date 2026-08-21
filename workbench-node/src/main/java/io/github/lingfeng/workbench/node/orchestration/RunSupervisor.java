package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.context.ContextRegistry;
import io.github.lingfeng.workbench.node.context.ContextRegistry.ResolvedContext;
import io.github.lingfeng.workbench.node.context.ContextRegistryProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.MissionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RunSupervisor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RunSupervisor.class);

    private final NodeProperties properties;
    private final ControlLoopStore store;
    private final SessionRuntimeAdapter runtime;
    private final AcceptanceEvaluator acceptanceEvaluator;
    private final ContextRegistry contextRegistry;
    private final ExecutorService eventQueue;
    private NodeCommand.StartRun activeCommand;
    private SessionContext sessionContext;
    private SessionHandle sessionHandle;
    private NodeCommand.StartRun pendingStart;
    private boolean terminal;
    private boolean acceptancePending;

    public RunSupervisor(
            NodeProperties properties,
            ControlLoopStore store,
            SessionRuntimeAdapter runtime,
            AcceptanceEvaluator acceptanceEvaluator) {
        this(properties, store, runtime, acceptanceEvaluator,
                new ContextRegistry(properties, new ContextRegistryProperties(Map.of(), List.of())));
    }

    public RunSupervisor(
            NodeProperties properties,
            ControlLoopStore store,
            SessionRuntimeAdapter runtime,
            AcceptanceEvaluator acceptanceEvaluator,
            ContextRegistry contextRegistry) {
        this.properties = properties;
        this.store = store;
        this.runtime = runtime;
        this.acceptanceEvaluator = acceptanceEvaluator;
        this.contextRegistry = contextRegistry;
        this.eventQueue = Executors.newSingleThreadExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("run-supervisor").unstarted(runnable));
    }

    public void recover() {
        store.activeRun().ifPresent(recovery -> eventQueue.execute(() -> recover(recovery)));
    }

    public void acceptStoredCommand(NodeCommand command) {
        eventQueue.execute(() -> dispatch(command));
    }

    private void dispatch(NodeCommand command) {
        if (command instanceof NodeCommand.StartRun start) {
            startRun(start);
        } else if (command instanceof NodeCommand.InteractionResponse response) {
            provideInteractionResponse(response);
        } else if (command instanceof NodeCommand.CancelRun cancel) {
            cancelRun(cancel);
        }
    }

    private void startRun(NodeCommand.StartRun command) {
        if (activeCommand != null && !activeCommand.binding().runId().equals(command.binding().runId())) {
            if (terminal && pendingStart == null) {
                pendingStart = command;
                logger.info("Queued next Run while the terminal Session closes runId={}",
                        command.binding().runId());
            } else {
                logger.warn("Rejecting second active Run runId={}", command.binding().runId());
            }
            return;
        }
        activeCommand = command;
        try {
            if (!properties.runtimeKind().equals(command.runtimeKind())) {
                throw new IllegalArgumentException("Command runtimeKind is not configured on this Node");
            }
            ResolvedContext resolved = contextRegistry.resolve(command.workspaceRef(), command.contextRefs());
            sessionContext = new SessionContext(
                    command, resolved.workspace(), resolved.contextPaths(),
                    store.evidenceDirectory(command.binding().runId()));
        } catch (RuntimeException exception) {
            recordUnknown("Run configuration failed closed: " + exception.getMessage());
            return;
        }
        store.markOpeningSession(command.binding().runId());
        runtime.openSession(sessionContext, runtimeSink()).whenComplete((openedHandle, failure) ->
                eventQueue.execute(() -> finishOpen(openedHandle, failure)));
    }

    private void finishOpen(SessionHandle openedHandle, Throwable failure) {
        if (failure != null || openedHandle == null) {
            recordUnknown("Runtime Session could not be opened");
            return;
        }
        sessionHandle = openedHandle;
        store.saveSession(activeCommand.binding().runId(), openedHandle, true);
        store.recordRunStarted(activeCommand.binding().runId(), true);
        MissionInput mission = new MissionInput(
                activeCommand.objective(), activeCommand.acceptanceSummary(),
                activeCommand.authorizedSideEffectsSummary(), activeCommand.executionProfile());
        runtime.submitMission(sessionHandle, mission, runtimeSink()).whenComplete((unused, submitFailure) -> {
            if (submitFailure != null) {
                eventQueue.execute(() -> recordUnknown("Runtime rejected the Mission prompt"));
            }
        });
    }

    private void provideInteractionResponse(NodeCommand.InteractionResponse response) {
        if (terminal || activeCommand == null || sessionHandle == null) {
            return;
        }
        InteractionInput input = new InteractionInput(
                response.commandId(), response.interactionId(), response.checkpointId(),
                response.decision(), response.responseSummary());
        runtime.provideInteractionResponse(sessionHandle, input, runtimeSink()).whenComplete((unused, failure) ->
                eventQueue.execute(() -> {
                    if (failure != null) {
                        logger.warn("Runtime did not consume durable Interaction response runId={} commandId={}",
                                response.binding().runId(), response.commandId());
                        return;
                    }
                    store.recordInteractionConsumed(response);
                }));
    }

    private void cancelRun(NodeCommand.CancelRun cancel) {
        if (terminal) {
            return;
        }
        if (sessionHandle == null) {
            recordInterrupted("Run cancelled before an Agent Session was available");
            return;
        }
        runtime.cancel(sessionHandle, cancel.reasonSummary(), runtimeSink()).whenComplete((unused, failure) ->
                eventQueue.execute(() -> {
                    if (failure != null) {
                        recordUnknown("Runtime cancellation outcome is unknown");
                    } else {
                        recordInterrupted("OpenCode Session was aborted");
                    }
                }));
    }

    private void handleRuntimeEvent(String expectedRunId, NormalizedRuntimeEvent event) {
        if (terminal || activeCommand == null
                || !activeCommand.binding().runId().equals(expectedRunId)) {
            return;
        }
        String runId = activeCommand.binding().runId();
        if (event instanceof NormalizedRuntimeEvent.PhaseChanged phase) {
            store.recordPhase(runId, phase.phaseCode(), phase.summary());
        } else if (event instanceof NormalizedRuntimeEvent.ProgressUpdated progress) {
            store.recordProgress(runId, progress.summary());
        } else if (event instanceof NormalizedRuntimeEvent.InteractionRequested interaction) {
            store.recordInteraction(runId, interaction.interactionId(), interaction.checkpointId(),
                    interaction.promptSummary(), interaction.allowedDecisions(), interaction.resumable());
        } else if (event instanceof NormalizedRuntimeEvent.RuntimeIdle idle) {
            evaluateAcceptance(idle.resultSummary());
        } else if (event instanceof NormalizedRuntimeEvent.SessionFailed failed) {
            recordUnknown(failed.summary());
        }
    }

    private void evaluateAcceptance(String runtimeSummary) {
        if (acceptancePending || terminal) {
            return;
        }
        acceptancePending = true;
        acceptanceEvaluator.evaluate(sessionContext, sessionHandle, runtimeSummary)
                .whenComplete((result, failure) -> eventQueue.execute(() -> {
                    acceptancePending = false;
                    if (failure != null || result == null) {
                        recordUnknown("Independent acceptance evaluation failed");
                        return;
                    }
                    completeTerminal(
                            activeCommand.binding().runId(),
                            NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                            result.status(), result.summary());
                }));
    }

    private void recordUnknown(String summary) {
        completeTerminal(
                activeCommand.binding().runId(), NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, summary);
    }

    private void recordInterrupted(String summary) {
        completeTerminal(
                activeCommand.binding().runId(), NormalizedRuntimeEvent.RuntimeOutcome.INTERRUPTED,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, summary);
    }

    private void completeTerminal(
            String runId,
            NormalizedRuntimeEvent.RuntimeOutcome runtimeOutcome,
            NormalizedRuntimeEvent.AcceptanceStatus acceptanceStatus,
            String summary) {
        terminal = store.tryRecordTerminal(runId, runtimeOutcome, acceptanceStatus, summary);
        if (!terminal) {
            return;
        }
        SessionHandle completedSession = sessionHandle;
        if (completedSession == null) {
            resetAfterTerminal();
            return;
        }
        runtime.closeSession(completedSession, ignored -> { }).whenComplete((unused, failure) ->
                eventQueue.execute(() -> {
                    if (failure != null) {
                        logger.warn("Terminal Session close failed runId={}", runId);
                    }
                    resetAfterTerminal();
                }));
    }

    private void resetAfterTerminal() {
        NodeCommand.StartRun next = pendingStart;
        pendingStart = null;
        activeCommand = null;
        sessionContext = null;
        sessionHandle = null;
        terminal = false;
        acceptancePending = false;
        if (next != null) {
            startRun(next);
        }
    }

    private Consumer<NormalizedRuntimeEvent> runtimeSink() {
        String expectedRunId = activeCommand.binding().runId();
        return event -> {
            if (!eventQueue.isShutdown()) {
                eventQueue.execute(() -> handleRuntimeEvent(expectedRunId, event));
            }
        };
    }

    private void recover(ControlLoopStore.RecoveryRun recovery) {
        activeCommand = recovery.command();
        if (recovery.sessionHandle() == null) {
            if (recovery.state().equals("received")) {
                startRun(recovery.command());
            } else {
                recordUnknown("Node restarted without a durable Agent Session identity");
            }
            return;
        }
        try {
            ResolvedContext resolved = contextRegistry.resolve(
                    recovery.command().workspaceRef(), recovery.command().contextRefs());
            sessionContext = new SessionContext(
                    recovery.command(), resolved.workspace(), resolved.contextPaths(),
                    recovery.evidenceDirectory());
        } catch (RuntimeException exception) {
            recordUnknown("Node could not restore the original workspace binding");
            return;
        }
        sessionHandle = new SessionHandle(
                recovery.sessionHandle(), recovery.runtimeIdentity(), recovery.runtimeVersion(),
                recovery.workspaceDirectory());
        runtime.reattach(sessionHandle, sessionContext, runtimeSink())
                .whenComplete((inspection, failure) -> eventQueue.execute(() ->
                        finishReattach(recovery, inspection, failure)));
    }

    private void finishReattach(
            ControlLoopStore.RecoveryRun recovery, SessionInspection inspection, Throwable failure) {
        if (failure != null || inspection == null || !inspection.sameSession() || !inspection.alive()) {
            recordUnknown("Runtime could not prove the original Agent Session identity");
            return;
        }
        if (recovery.state().equals("opening_session")) {
            store.recordRunStarted(recovery.command().binding().runId(), true);
        }
        if (recovery.state().equals("waiting_interaction")) {
            store.storedInteractionResponse(recovery.command().binding().runId())
                    .ifPresent(this::provideInteractionResponse);
        }
    }

    @Override
    public void close() {
        eventQueue.shutdown();
        try {
            if (!eventQueue.awaitTermination(5, TimeUnit.SECONDS)) {
                eventQueue.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            eventQueue.shutdownNow();
        }
    }
}
