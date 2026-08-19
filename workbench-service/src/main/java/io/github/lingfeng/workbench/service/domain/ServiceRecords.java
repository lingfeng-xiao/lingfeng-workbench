package io.github.lingfeng.workbench.service.domain;

import io.github.lingfeng.workbench.service.domain.Statuses.MissionStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RunStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.WorkItemStatus;
import java.time.Instant;
import java.util.List;

public final class ServiceRecords {
    private ServiceRecords() {}

    public record WorkItem(
            String id, String title, WorkItemStatus status, int priority, Instant createdAt, Instant updatedAt) {}

    public record Mission(
            String id,
            String workItemId,
            int revision,
            String digest,
            String objective,
            String acceptanceSummary,
            String authorizedSideEffectsSummary,
            String targetNodeId,
            String workspaceRef,
            String runtimeKind,
            String executionProfile,
            MissionStatus status,
            Instant createdAt,
            Instant updatedAt) {}

    public record Run(
            String id,
            String missionId,
            String nodeId,
            String commandId,
            RunStatus status,
            String progressSummary,
            String resultSummary,
            boolean resumable,
            Instant createdAt,
            Instant updatedAt) {}

    public record Node(
            String id, String displayName, List<String> capabilities, Instant lastHeartbeatAt) {}

    public record Timeline(String id, String runId, String eventType, String summary, Instant createdAt) {}

    public record IdempotencyRecord(
            String key, String requestHash, String workItemId, String missionId, String missionDigest, Instant createdAt) {}

    public record NodeMessage(String messageId, String nodeId, String messageType, String payloadHash, Instant createdAt) {}

    public record Interaction(
            String id,
            String runId,
            String checkpointId,
            String missionDigest,
            String state,
            String promptSummary,
            Instant createdAt) {}
}
