package io.github.lingfeng.workbench.service.api.client;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.IDENTIFIER;

import io.github.lingfeng.workbench.service.domain.Statuses.MissionStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RunStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.WorkItemStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ClientDtos {
    private ClientDtos() {}

    public record CreateWorkItemRequest(
            @NotBlank @Size(max = 800) String title,
            @NotBlank @Size(max = 800) String objective,
            @NotBlank @Size(max = 800) String acceptanceSummary,
            @NotBlank @Size(max = 800) String authorizedSideEffectsSummary,
            @NotBlank @Pattern(regexp = IDENTIFIER) String targetNodeId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String workspaceRef,
            @NotBlank @Pattern(regexp = IDENTIFIER) String runtimeKind,
            @NotBlank @Pattern(regexp = IDENTIFIER) String executionProfile,
            @Min(-100) @Max(100) Integer priority,
            @NotNull Boolean dataBoundaryAcknowledged) {
        public int effectivePriority() { return priority == null ? 0 : priority; }
    }

    public record CreatedWorkItem(String workItemId, String missionId, String missionDigest, Instant createdAt) {}

    public record WorkItemSummary(
            String workItemId, String title, WorkItemStatus status, int priority, Instant updatedAt) {}

    public record WorkItemDetail(
            String workItemId,
            String title,
            WorkItemStatus status,
            int priority,
            Instant updatedAt,
            List<MissionDetail> missions) {}

    public record MissionDetail(
            String missionId,
            String workItemId,
            int revision,
            String missionDigest,
            String objective,
            String acceptanceSummary,
            String authorizedSideEffectsSummary,
            String targetNodeId,
            String workspaceRef,
            String runtimeKind,
            String executionProfile,
            MissionStatus status,
            List<RunSummary> runs,
            Instant createdAt,
            Instant updatedAt) {}

    public record RunSummary(
            String runId,
            String missionId,
            String nodeId,
            RunStatus status,
            String progressSummary,
            String resultSummary,
            boolean resumable,
            Instant updatedAt) {}

    public record RunDetail(
            String runId,
            String missionId,
            String nodeId,
            RunStatus status,
            String progressSummary,
            String resultSummary,
            boolean resumable,
            Instant updatedAt,
            List<TimelineEvent> timeline) {}

    public record TimelineEvent(String eventId, String eventType, String summary, Instant createdAt) {}

    public record NodeSummary(
            String nodeId, String displayName, String status, List<String> capabilities, Instant lastHeartbeatAt) {}

    public record InteractionSummary(
            String interactionId,
            String runId,
            String checkpointId,
            String missionDigest,
            String state,
            String promptSummary,
            Instant createdAt) {}
}
