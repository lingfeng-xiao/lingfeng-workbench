package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.connection.NodeProtocolClient;
import io.github.lingfeng.workbench.node.connection.ProtocolClientException;
import io.github.lingfeng.workbench.node.localstate.LocalNodeStore;
import io.github.lingfeng.workbench.node.localstate.PendingOutboxEvent;
import io.github.lingfeng.workbench.node.localstate.RunRegistration;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import io.github.lingfeng.workbench.node.protocol.ProtocolAck;
import io.github.lingfeng.workbench.node.runtime.RuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.RuntimeExecutionContext;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AssignmentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentExecutor.class);

    private final NodeProperties properties;
    private final LocalNodeStore localNodeStore;
    private final NodeProtocolClient protocolClient;
    private final RuntimeAdapter runtimeAdapter;
    private final NodeEventFactory eventFactory;

    public AssignmentExecutor(
            NodeProperties properties,
            LocalNodeStore localNodeStore,
            NodeProtocolClient protocolClient,
            RuntimeAdapter runtimeAdapter,
            NodeEventFactory eventFactory) {
        this.properties = properties;
        this.localNodeStore = localNodeStore;
        this.protocolClient = protocolClient;
        this.runtimeAdapter = runtimeAdapter;
        this.eventFactory = eventFactory;
    }

    public void execute(Assignment assignment) {
        validateAssignment(assignment);
        RunRegistration registration = localNodeStore.registerAssignment(assignment);
        if (registration.status() != RunRegistration.Status.NEW) {
            logger.info("Ignoring duplicate assignment commandId={} runId={} localStatus={}",
                    assignment.commandId(), assignment.runId(), registration.status());
            return;
        }

        enqueueAndAttemptDelivery(eventFactory.runAccepted(assignment), "ACCEPTED", false, null);
        Path workspace = properties.resolveWorkspace(assignment.workspaceRef());
        AtomicBoolean terminalRecorded = new AtomicBoolean(false);
        RuntimeExecutionContext executionContext = new RuntimeExecutionContext(
                assignment, workspace, registration.evidenceDirectory());
        try {
            runtimeAdapter.start(executionContext, event -> translateRuntimeEvent(
                    assignment, event, terminalRecorded));
            if (terminalRecorded.compareAndSet(false, true)) {
                recordFailedTerminal(
                        assignment,
                        "Runtime event stream ended without a terminal",
                        RuntimeEvent.AcceptanceStatus.UNKNOWN);
            }
        } catch (RuntimeException exception) {
            logger.error("Runtime execution failed runId={}", assignment.runId(), exception);
            runtimeAdapter.cancel();
            if (terminalRecorded.compareAndSet(false, true)) {
                recordFailedTerminal(
                        assignment,
                        "Runtime execution failed; details remain on the node",
                        RuntimeEvent.AcceptanceStatus.UNKNOWN);
            }
        }
    }

    public void flushOutbox() {
        for (PendingOutboxEvent pending : localNodeStore.pendingEvents(100)) {
            OutboundEvent event = pending.outboundEvent();
            ProtocolAck acknowledgement = protocolClient.sendEvent(event);
            if (!event.messageId().equals(acknowledgement.requestMessageId())) {
                throw new ProtocolClientException("Service acknowledged a different message", false);
            }
            localNodeStore.acknowledgeEvent(event.messageId());
        }
    }

    private void translateRuntimeEvent(
            Assignment assignment,
            RuntimeEvent runtimeEvent,
            AtomicBoolean terminalRecorded) {
        if (terminalRecorded.get()) {
            logger.warn("Ignoring runtime event after terminal runId={} event={}",
                    assignment.runId(), runtimeEvent.getClass().getSimpleName());
            return;
        }
        if (runtimeEvent instanceof RuntimeEvent.Started started) {
            enqueueAndAttemptDelivery(
                    eventFactory.runStarted(assignment, started.resumable()),
                    "RUNNING",
                    started.resumable(),
                    started.runtimeSessionRef());
        } else if (runtimeEvent instanceof RuntimeEvent.Progress progress) {
            enqueueAndAttemptDelivery(eventFactory.progress(assignment, progress.summary()), "RUNNING", false, null);
            deliverHeartbeatBestEffort(assignment.runId());
        } else if (runtimeEvent instanceof RuntimeEvent.InteractionRequested interactionRequested) {
            runtimeAdapter.cancel();
            if (terminalRecorded.compareAndSet(false, true)) {
                recordFailedTerminal(
                        assignment,
                        "Runtime requested interaction; Interaction is deferred to MVP-N2 (checkpoint "
                                + interactionRequested.checkpointId() + ")",
                        RuntimeEvent.AcceptanceStatus.UNKNOWN);
            }
        } else if (runtimeEvent instanceof RuntimeEvent.Finished finished) {
            if (terminalRecorded.compareAndSet(false, true)) {
                String eventType = finished.runtimeOutcome() == RuntimeEvent.RuntimeOutcome.SUCCEEDED
                        ? "EXECUTION_FINISHED"
                        : finished.runtimeOutcome() == RuntimeEvent.RuntimeOutcome.INTERRUPTED
                                ? "EXECUTION_INTERRUPTED"
                                : "EXECUTION_FAILED";
                enqueueAndAttemptDelivery(
                        eventFactory.terminal(
                                assignment,
                                eventType,
                                finished.runtimeOutcome(),
                                finished.acceptanceStatus(),
                                finished.resultSummary()),
                        localTerminalState(eventType),
                        false,
                        null);
            }
        } else if (runtimeEvent instanceof RuntimeEvent.Failed failed) {
            if (terminalRecorded.compareAndSet(false, true)) {
                recordFailedTerminal(assignment, failed.summary(), failed.acceptanceStatus());
            }
        } else if (runtimeEvent instanceof RuntimeEvent.Interrupted interrupted) {
            if (terminalRecorded.compareAndSet(false, true)) {
                enqueueAndAttemptDelivery(
                        eventFactory.terminal(
                                assignment,
                                "EXECUTION_INTERRUPTED",
                                RuntimeEvent.RuntimeOutcome.INTERRUPTED,
                                RuntimeEvent.AcceptanceStatus.UNKNOWN,
                                interrupted.summary()),
                        "INTERRUPTED",
                        false,
                        null);
            }
        }
    }

    private void recordFailedTerminal(
            Assignment assignment,
            String summary,
            RuntimeEvent.AcceptanceStatus acceptanceStatus) {
        enqueueAndAttemptDelivery(
                eventFactory.terminal(
                        assignment,
                        "EXECUTION_FAILED",
                        RuntimeEvent.RuntimeOutcome.FAILED,
                        acceptanceStatus,
                        summary),
                "FAILED",
                false,
                null);
    }

    private void enqueueAndAttemptDelivery(
            OutboundEvent event,
            String localState,
            boolean resumable,
            String runtimeSessionRef) {
        localNodeStore.enqueueEvent(event, localState, resumable, runtimeSessionRef);
        try {
            flushOutbox();
        } catch (ProtocolClientException exception) {
            if (!exception.isRetryable()) {
                throw exception;
            }
            logger.warn("Event remains in local outbox messageId={} runId={}", event.messageId(), event.runId());
        }
    }

    private void deliverHeartbeatBestEffort(String runId) {
        try {
            protocolClient.heartbeat(runId);
        } catch (ProtocolClientException exception) {
            if (!exception.isRetryable()) {
                throw exception;
            }
            logger.warn("Heartbeat delivery failed runId={}; durable events remain queued", runId);
        }
    }

    private void validateAssignment(Assignment assignment) {
        if (!properties.nodeId().equals(assignment.targetNodeId())) {
            throw new IllegalArgumentException("Assignment targets another node");
        }
        if (!properties.runtimeKind().equals(assignment.runtimeKind())) {
            throw new IllegalArgumentException("Assignment requests unsupported runtimeKind");
        }
        if (assignment.missionDigest() == null || !assignment.missionDigest().matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Assignment missionDigest is invalid");
        }
        requireShortText(assignment.objective(), "objective");
        requireShortText(assignment.acceptanceSummary(), "acceptanceSummary");
        requireShortText(assignment.authorizedSideEffectsSummary(), "authorizedSideEffectsSummary");
    }

    private static void requireShortText(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 800) {
            throw new IllegalArgumentException(fieldName + " must contain 1 to 800 characters");
        }
    }

    private static String localTerminalState(String eventType) {
        return switch (eventType) {
            case "EXECUTION_FINISHED" -> "FINISHED";
            case "EXECUTION_INTERRUPTED" -> "INTERRUPTED";
            default -> "FAILED";
        };
    }
}
