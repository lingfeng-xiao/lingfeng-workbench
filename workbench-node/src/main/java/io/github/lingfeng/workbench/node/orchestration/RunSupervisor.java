package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RunSupervisor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RunSupervisor.class);
    private static final int REQUIRED_FAKE_TURNS = 3;

    private final NodeProperties properties;
    private final ControlLoopStore store;
    private final SessionRuntimeAdapter runtime;
    private final ExecutorService eventQueue;
    private NodeCommand.StartRun activeCommand;
    private SessionHandle sessionHandle;
    private int currentTurn;
    private boolean terminal;

    public RunSupervisor(NodeProperties properties, ControlLoopStore store, SessionRuntimeAdapter runtime) {
        this.properties = properties;
        this.store = store;
        this.runtime = runtime;
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
            logger.warn("Rejecting second active Run runId={}", command.binding().runId());
            return;
        }
        activeCommand = command;
        Path workspace;
        try {
            if (!properties.runtimeKind().equals(command.runtimeKind())) {
                throw new IllegalArgumentException("Command runtimeKind is not configured on this Node");
            }
            workspace = properties.resolveWorkspace(command.workspaceRef());
        } catch (RuntimeException exception) {
            recordUnknown("Run configuration failed closed: " + exception.getMessage());
            return;
        }
        store.markOpeningSession(command.binding().runId());
        SessionContext context = new SessionContext(
                command, workspace, store.evidenceDirectory(command.binding().runId()));
        runtime.openSession(context, runtimeSink()).whenComplete((openedHandle, failure) ->
                eventQueue.execute(() -> finishOpen(openedHandle, failure)));
    }

    private void finishOpen(SessionHandle openedHandle, Throwable failure) {
        if (failure != null) {
            recordUnknown("Runtime Session could not be opened");
            return;
        }
        sessionHandle = openedHandle;
        store.saveSession(activeCommand.binding().runId(), openedHandle.opaqueReference(),
                runtime.capabilities().supports("resume"));
        store.recordRunStarted(activeCommand.binding().runId(), runtime.capabilities().supports("resume"));
        submitTurn(1);
    }

    private void submitTurn(int turnNumber) {
        if (terminal || sessionHandle == null) {
            return;
        }
        currentTurn = turnNumber;
        String turnId = "turn-" + turnNumber;
        store.recordTurn(activeCommand.binding().runId(), turnId, turnNumber, "submitted");
        TurnInput input = new TurnInput(turnId, turnNumber,
                turnNumber == 1 ? activeCommand.objective() : "Continue the same immutable Mission");
        runtime.submitTurn(sessionHandle, input, runtimeSink()).whenComplete((unused, failure) -> {
            if (failure != null) {
                eventQueue.execute(() -> recordUnknown("Runtime rejected a Turn"));
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
        CompletionStage<Void> delivery;
        try {
            delivery = runtime.provideInteractionResponse(sessionHandle, input, runtimeSink());
        } catch (RuntimeException exception) {
            logger.warn("Runtime did not accept durable Interaction response yet runId={} commandId={}",
                    response.binding().runId(), response.commandId());
            return;
        }
        delivery.whenComplete((unused, failure) ->
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
        runtime.cancel(sessionHandle, cancel.reasonSummary(), runtimeSink()).whenComplete((unused, failure) -> {
            if (failure != null) {
                eventQueue.execute(() -> recordUnknown("Runtime cancellation outcome is unknown"));
            }
        });
    }

    private void handleRuntimeEvent(NormalizedRuntimeEvent event) {
        if (terminal || activeCommand == null) {
            return;
        }
        String runId = activeCommand.binding().runId();
        if (event instanceof NormalizedRuntimeEvent.TurnAccepted accepted) {
            store.recordTurn(runId, accepted.turnId(), turnNumber(accepted.turnId()), "accepted");
        } else if (event instanceof NormalizedRuntimeEvent.PhaseChanged phase) {
            store.recordPhase(runId, phase.phaseCode(), phase.summary());
        } else if (event instanceof NormalizedRuntimeEvent.ProgressUpdated progress) {
            store.recordProgress(runId, progress.summary());
        } else if (event instanceof NormalizedRuntimeEvent.InteractionRequested interaction) {
            store.recordInteraction(runId, interaction.interactionId(), interaction.checkpointId(),
                    interaction.promptSummary(), interaction.allowedDecisions(), interaction.resumable());
            runtime.pause(sessionHandle, runtimeSink());
        } else if (event instanceof NormalizedRuntimeEvent.TurnFinished finished) {
            int turnNumber = turnNumber(finished.turnId());
            store.recordTurn(runId, finished.turnId(), turnNumber, "finished");
            if (turnNumber < REQUIRED_FAKE_TURNS) {
                submitTurn(turnNumber + 1);
            }
        } else if (event instanceof NormalizedRuntimeEvent.SessionFailed failed) {
            recordUnknown(failed.summary());
        } else if (event instanceof NormalizedRuntimeEvent.Terminal runtimeTerminal) {
            recordTerminal(runtimeTerminal);
        }
    }

    private void recordTerminal(NormalizedRuntimeEvent.Terminal runtimeTerminal) {
        if (!activeCommand.binding().missionDigest().equals(runtimeTerminal.missionDigest())) {
            recordUnknown("Runtime terminal mission digest did not match the immutable Mission");
            return;
        }
        NormalizedRuntimeEvent.RuntimeOutcome outcome = runtimeTerminal.runtimeOutcome();
        NormalizedRuntimeEvent.AcceptanceStatus acceptance = runtimeTerminal.acceptanceStatus();
        if (acceptance == NormalizedRuntimeEvent.AcceptanceStatus.PASSED
                && outcome != NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED) {
            recordUnknown("Runtime claimed PASSED without SUCCEEDED");
            return;
        }
        terminal = store.tryRecordTerminal(
                activeCommand.binding().runId(), outcome, acceptance, runtimeTerminal.resultSummary());
    }

    private void recordUnknown(String summary) {
        terminal = store.tryRecordTerminal(
                activeCommand.binding().runId(), NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, summary);
    }

    private void recordInterrupted(String summary) {
        terminal = store.tryRecordTerminal(
                activeCommand.binding().runId(), NormalizedRuntimeEvent.RuntimeOutcome.INTERRUPTED,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, summary);
    }

    private Consumer<NormalizedRuntimeEvent> runtimeSink() {
        return event -> {
            if (!eventQueue.isShutdown()) {
                eventQueue.execute(() -> handleRuntimeEvent(event));
            }
        };
    }

    private void recover(ControlLoopStore.RecoveryRun recovery) {
        activeCommand = recovery.command();
        currentTurn = recovery.currentTurn();
        if (recovery.sessionHandle() == null) {
            if (recovery.state().equals("received")) {
                startRun(recovery.command());
            } else {
                recordUnknown("Node restarted without a durable Agent Session identity");
            }
            return;
        }
        sessionHandle = new SessionHandle(recovery.sessionHandle());
        if (!recovery.resumable() || !runtime.capabilities().supports("resume")) {
            recordUnknown("Runtime does not support recovery of the durable Agent Session");
            return;
        }
        runtime.inspect(sessionHandle).whenComplete((inspection, inspectFailure) ->
                eventQueue.execute(() -> finishInspect(recovery, inspection, inspectFailure)));
    }

    private void finishInspect(
            ControlLoopStore.RecoveryRun recovery, SessionInspection inspection, Throwable failure) {
        if (failure != null || inspection == null || !inspection.sameSession()
                || !inspection.alive() || !inspection.resumable()) {
            recordUnknown("Runtime could not prove the original Agent Session identity");
            return;
        }
        String checkpoint = recovery.checkpointId() == null ? "restart" : recovery.checkpointId();
        runtime.resume(sessionHandle, checkpoint, runtimeSink()).whenComplete((unused, resumeFailure) ->
                eventQueue.execute(() -> {
                    if (resumeFailure != null) {
                        recordUnknown("Runtime failed to resume the original Agent Session");
                        return;
                    }
                    if (recovery.state().equals("opening_session")) {
                        store.recordRunStarted(recovery.command().binding().runId(), true);
                    }
                    if (recovery.state().equals("waiting_interaction")) {
                        store.storedInteractionResponse(recovery.command().binding().runId())
                                .ifPresent(this::provideInteractionResponse);
                    } else if (recovery.currentTurn() == 0) {
                        submitTurn(1);
                    }
                }));
    }

    private static int turnNumber(String turnId) {
        try {
            return Integer.parseInt(turnId.substring(turnId.lastIndexOf('-') + 1));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Runtime emitted an invalid local Turn ID", exception);
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
