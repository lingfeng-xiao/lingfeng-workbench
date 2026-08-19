package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.Acknowledgement;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.AssignmentCommand;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.EventType;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.HeartbeatRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.HelloRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.NoCommand;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.PollRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.RunEvent;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Mission;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.NodeMessage;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Run;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Timeline;
import io.github.lingfeng.workbench.service.domain.Statuses.AcceptanceStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.MissionStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RunStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RuntimeOutcome;
import io.github.lingfeng.workbench.service.domain.Statuses.WorkItemStatus;
import io.github.lingfeng.workbench.service.persistence.WorkbenchRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeApplicationService {
    private final WorkbenchRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NodeApplicationService(WorkbenchRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    NodeApplicationService(WorkbenchRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Acknowledgement registerNode(HelloRequest request) {
        ReplayDecision replay = inspectReplay(request.messageId(), request.nodeId(), "HELLO", request);
        if (replay.duplicate()) {
            return new Acknowledgement(request.messageId(), true);
        }
        Instant now = clock.instant();
        repository.upsertNode(request.nodeId(), request.displayName(), request.capabilities(), now);
        recordMessage(request.messageId(), request.nodeId(), "HELLO", replay.payloadHash(), now);
        return new Acknowledgement(request.messageId(), false);
    }

    @Transactional
    public Acknowledgement heartbeat(HeartbeatRequest request) {
        ReplayDecision replay = inspectReplay(request.messageId(), request.nodeId(), "HEARTBEAT", request);
        if (replay.duplicate()) {
            return new Acknowledgement(request.messageId(), true);
        }
        if (request.activeRunId() != null) {
            Run activeRun = repository.findRun(request.activeRunId())
                    .orElseThrow(() -> DomainException.rejected("activeRunId does not exist"));
            if (!activeRun.nodeId().equals(request.nodeId())) {
                throw DomainException.rejected("activeRunId is not owned by this node");
            }
        }
        Instant now = clock.instant();
        if (!repository.updateHeartbeat(request.nodeId(), now)) {
            throw DomainException.rejected("Node must call hello before heartbeat");
        }
        recordMessage(request.messageId(), request.nodeId(), "HEARTBEAT", replay.payloadHash(), now);
        return new Acknowledgement(request.messageId(), false);
    }

    @Transactional
    public Object poll(PollRequest request) {
        ensureRegistered(request.nodeId());
        Instant now = clock.instant();
        repository.acknowledgeCommands(request.nodeId(), request.effectiveAcknowledgedCommandIds(), now);
        Run existingAssignment = repository.findUnacknowledgedAssignment(request.nodeId()).orElse(null);
        if (existingAssignment != null) {
            return assignment(existingAssignment);
        }
        Mission mission = repository.findFirstPendingMission(request.nodeId()).orElse(null);
        if (mission == null) {
            return new NoCommand();
        }
        if (!repository.assignMission(mission.id(), now)) {
            throw DomainException.conflict("Mission was concurrently assigned");
        }
        Run run = new Run(newId("run_"), mission.id(), request.nodeId(), newId("cmd_"), RunStatus.assigned,
                null, null, false, now, now);
        repository.insertRun(run);
        repository.updateWorkItemStatus(mission.workItemId(), WorkItemStatus.in_progress, now);
        repository.insertTimeline(new Timeline(newId("ev_"), run.id(), "ASSIGNED", "Mission assigned", now));
        return assignment(run);
    }

    @Transactional
    public Acknowledgement recordEvent(RunEvent event) {
        validateEventShape(event);
        ReplayDecision replay = inspectReplay(event.messageId(), event.nodeId(), event.eventType().name(), event);
        if (replay.duplicate()) {
            return new Acknowledgement(event.messageId(), true);
        }
        Run run = repository.findRun(event.runId())
                .orElseThrow(() -> DomainException.rejected("runId does not exist"));
        Mission mission = repository.findMission(event.missionId())
                .orElseThrow(() -> DomainException.rejected("missionId does not exist"));
        validateRunBinding(event, run, mission);
        Instant now = clock.instant();
        applyEvent(event, run, mission, now);
        recordMessage(event.messageId(), event.nodeId(), event.eventType().name(), replay.payloadHash(), now);
        return new Acknowledgement(event.messageId(), false);
    }

    private void applyEvent(RunEvent event, Run run, Mission mission, Instant now) {
        switch (event.eventType()) {
            case RUN_ACCEPTED -> acceptRun(event, run, now);
            case RUN_STARTED -> startRun(event, run, mission, now);
            case PROGRESS -> updateProgress(event, run, now);
            case EXECUTION_FINISHED, EXECUTION_FAILED, EXECUTION_INTERRUPTED ->
                    finishRun(event, run, mission, now);
        }
        repository.insertTimeline(new Timeline("ev_" + event.messageId(), run.id(), event.eventType().name(),
                event.progressSummary() != null ? event.progressSummary() : event.resultSummary(), now));
    }

    private void acceptRun(RunEvent event, Run run, Instant now) {
        requireStatus(run, RunStatus.assigned);
        if (!run.commandId().equals(event.commandId())) {
            throw DomainException.conflict("commandId does not match the assignment");
        }
        repository.acknowledgeCommand(event.nodeId(), event.commandId(), now);
    }

    private void startRun(RunEvent event, Run run, Mission mission, Instant now) {
        requireStatus(run, RunStatus.assigned);
        repository.updateRunStatus(run.id(), RunStatus.running, Boolean.TRUE.equals(event.resumable()), null, null, now);
        repository.updateMissionStatus(mission.id(), MissionStatus.running, now);
        repository.updateWorkItemStatus(mission.workItemId(), WorkItemStatus.in_progress, now);
    }

    private void updateProgress(RunEvent event, Run run, Instant now) {
        requireStatus(run, RunStatus.running);
        repository.updateRunStatus(run.id(), RunStatus.running, run.resumable(), event.progressSummary(), null, now);
    }

    private void finishRun(RunEvent event, Run run, Mission mission, Instant now) {
        if (run.status() == RunStatus.assigned) {
            if (event.eventType() == EventType.EXECUTION_FINISHED
                    || event.acceptanceStatus() == AcceptanceStatus.PASSED) {
                throw DomainException.conflict("A successful terminal requires a started Run");
            }
        } else {
            requireStatus(run, RunStatus.running);
        }
        validateTerminalConsistency(event);
        RunStatus runStatus;
        MissionStatus missionStatus;
        WorkItemStatus workItemStatus;
        if (event.eventType() == EventType.EXECUTION_FINISHED
                && event.runtimeOutcome() == RuntimeOutcome.SUCCEEDED
                && event.acceptanceStatus() == AcceptanceStatus.PASSED) {
            runStatus = RunStatus.completed;
            missionStatus = MissionStatus.completed;
            workItemStatus = WorkItemStatus.completed;
        } else if (event.eventType() == EventType.EXECUTION_INTERRUPTED) {
            runStatus = RunStatus.interrupted;
            missionStatus = MissionStatus.interrupted;
            workItemStatus = WorkItemStatus.attention_required;
        } else if (event.acceptanceStatus() == AcceptanceStatus.UNKNOWN) {
            runStatus = RunStatus.uncertain;
            missionStatus = MissionStatus.uncertain;
            workItemStatus = WorkItemStatus.attention_required;
        } else {
            runStatus = RunStatus.failed;
            missionStatus = MissionStatus.failed;
            workItemStatus = WorkItemStatus.attention_required;
        }
        repository.updateRunStatus(run.id(), runStatus, run.resumable(), null, event.resultSummary(), now);
        repository.updateMissionStatus(mission.id(), missionStatus, now);
        repository.updateWorkItemStatus(mission.workItemId(), workItemStatus, now);
    }

    private void validateTerminalConsistency(RunEvent event) {
        boolean consistent = switch (event.eventType()) {
            case EXECUTION_FINISHED -> event.runtimeOutcome() == RuntimeOutcome.SUCCEEDED;
            case EXECUTION_FAILED -> event.runtimeOutcome() == RuntimeOutcome.FAILED;
            case EXECUTION_INTERRUPTED -> event.runtimeOutcome() == RuntimeOutcome.INTERRUPTED;
            default -> false;
        };
        if (!consistent) {
            throw DomainException.rejected("eventType and runtimeOutcome are inconsistent");
        }
        if (event.acceptanceStatus() == AcceptanceStatus.PASSED
                && event.eventType() != EventType.EXECUTION_FINISHED) {
            throw DomainException.rejected("PASSED requires a successful finished Runtime");
        }
    }

    private void validateEventShape(RunEvent event) {
        switch (event.eventType()) {
            case RUN_ACCEPTED -> {
                require(event.commandId() != null, "RUN_ACCEPTED requires commandId");
                require(event.resumable() == null && event.progressSummary() == null && event.runtimeOutcome() == null
                        && event.acceptanceStatus() == null && event.resultSummary() == null,
                        "RUN_ACCEPTED contains fields from another event type");
            }
            case RUN_STARTED -> {
                require(event.resumable() != null, "RUN_STARTED requires resumable");
                require(event.commandId() == null && event.progressSummary() == null && event.runtimeOutcome() == null
                        && event.acceptanceStatus() == null && event.resultSummary() == null,
                        "RUN_STARTED contains fields from another event type");
            }
            case PROGRESS -> {
                require(event.progressSummary() != null, "PROGRESS requires progressSummary");
                require(event.commandId() == null && event.resumable() == null && event.runtimeOutcome() == null
                        && event.acceptanceStatus() == null && event.resultSummary() == null,
                        "PROGRESS contains fields from another event type");
            }
            case EXECUTION_FINISHED, EXECUTION_FAILED, EXECUTION_INTERRUPTED -> {
                require(event.runtimeOutcome() != null, "Terminal event requires runtimeOutcome");
                require(event.acceptanceStatus() != null, "Terminal event requires acceptanceStatus");
                require(event.resultSummary() != null, "Terminal event requires resultSummary");
                require(event.commandId() == null && event.resumable() == null && event.progressSummary() == null,
                        "Terminal event contains fields from another event type");
            }
        }
    }

    private void validateRunBinding(RunEvent event, Run run, Mission mission) {
        if (!run.nodeId().equals(event.nodeId()) || !run.missionId().equals(event.missionId())
                || !mission.workItemId().equals(event.workItemId()) || !mission.digest().equals(event.missionDigest())) {
            throw DomainException.conflict("Run event identifiers do not match durable state");
        }
    }

    private void requireStatus(Run run, RunStatus expected) {
        if (run.status() != expected) {
            throw DomainException.conflict("Run state " + run.status() + " does not allow this event");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw DomainException.rejected(message);
        }
    }

    private AssignmentCommand assignment(Run run) {
        Mission mission = repository.findMission(run.missionId())
                .orElseThrow(() -> new IllegalStateException("Assignment references a missing Mission"));
        return new AssignmentCommand("ASSIGNMENT", run.commandId(), mission.workItemId(), mission.id(), run.id(),
                mission.revision(), mission.digest(), mission.objective(), mission.acceptanceSummary(),
                mission.authorizedSideEffectsSummary(), mission.targetNodeId(), mission.workspaceRef(),
                mission.runtimeKind(), mission.executionProfile());
    }

    private void ensureRegistered(String nodeId) {
        if (repository.findNode(nodeId).isEmpty()) {
            throw DomainException.rejected("Node must call hello before polling");
        }
    }

    private ReplayDecision inspectReplay(String messageId, String nodeId, String messageType, Object payload) {
        String payloadHash = hashPayload(payload);
        NodeMessage existing = repository.findNodeMessage(messageId).orElse(null);
        if (existing == null) {
            return new ReplayDecision(false, payloadHash);
        }
        if (!existing.nodeId().equals(nodeId) || !existing.messageType().equals(messageType)
                || !existing.payloadHash().equals(payloadHash)) {
            throw DomainException.conflict("messageId was already used for different content");
        }
        return new ReplayDecision(true, payloadHash);
    }

    private void recordMessage(String messageId, String nodeId, String type, String payloadHash, Instant now) {
        repository.insertNodeMessage(new NodeMessage(messageId, nodeId, type, payloadHash, now));
    }

    private String hashPayload(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(payload)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Protocol message cannot be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private record ReplayDecision(boolean duplicate, String payloadHash) {}
}
