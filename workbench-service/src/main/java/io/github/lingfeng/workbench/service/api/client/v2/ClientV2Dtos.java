package io.github.lingfeng.workbench.service.api.client.v2;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.*;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ClientV2Dtos {
    private ClientV2Dtos() {}

    public record CreateWorkItemRequest(
            @NotBlank @Size(max=800) String title,
            @NotBlank @Size(max=800) String objective,
            @NotBlank @Size(max=800) String acceptanceSummary,
            @NotBlank @Size(max=800) String authorizedSideEffectsSummary,
            @NotBlank @Pattern(regexp=IDENTIFIER) String targetNodeId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String workspaceRef,
            @NotBlank @Pattern(regexp=IDENTIFIER) String runtimeKind,
            @NotBlank @Pattern(regexp=IDENTIFIER) String executionProfile,
            @Min(-100) @Max(100) Integer priority,
            @NotNull @AssertTrue Boolean dataBoundaryAcknowledged) {
        public int effectivePriority() { return priority == null ? 0 : priority; }
    }

    public record CreatedWorkItem(String workItemId, String missionId, String runId, int missionRevision,
                                  String missionDigest, Instant createdAt) {}
    public record WorkItemSummary(String workItemId, String title, String status, int priority, String phaseCode,
                                  String progressSummary, int waitingInteractionCount, Instant lastSyncedAt,
                                  Instant updatedAt) {}
    public record MissionProjection(String missionId, int revision, String objective,
                                    String acceptanceSummary, String status) {}
    public record RunProjection(String runId, String nodeId, String status, String phaseCode,
                                String progressSummary, String resultSummary, boolean resumable,
                                Instant lastSyncedAt) {}
    public record InteractionSummary(String interactionId, String runId, String checkpointId, String state,
                                     String promptSummary, List<String> allowedDecisions, String responseSummary,
                                     Instant resolvedAt, Instant consumedAt, Instant createdAt) {}
    public record NotificationProjection(String notificationId, String notificationType, String status,
                                         Instant createdAt) {}
    public record TimelineEvent(String eventId, String eventType, String summary, Instant createdAt) {}
    public record WorkItemDetail(String workItemId, String title, String status, int priority,
                                 MissionProjection mission, RunProjection run,
                                 List<InteractionSummary> interactions,
                                 List<NotificationProjection> notifications,
                                 List<TimelineEvent> timeline, Instant updatedAt) {}
    public record ResolveInteractionRequest(
            @NotBlank @Pattern(regexp=INTERACTION_ID) String interactionId,
            @NotBlank @Pattern(regexp=RUN_ID) String runId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String checkpointId,
            @NotBlank @Pattern(regexp=DIGEST) String missionDigest,
            @NotBlank @Pattern(regexp="APPROVE|REJECT|PROVIDE_INPUT") String decision,
            @NotBlank @Size(max=800) String responseSummary,
            @NotBlank @Pattern(regexp=IDENTIFIER) String resolvedBy,
            @NotNull Instant resolvedAt) {}
    public record InteractionResolution(String interactionId, String state, String commandId,
                                        boolean duplicate, Instant resolvedAt) {}
    public record NotificationPollRequest(
            @NotBlank @Pattern(regexp=IDENTIFIER) String requestId,
            @NotBlank @Pattern(regexp="owner") String targetAlias,
            @NotNull Instant sentAt) {}
    public record NoNotification(boolean notificationAvailable) { public NoNotification() { this(false); } }
    public record NotificationLease(boolean notificationAvailable, String notificationId, String notificationType,
                                    String targetAlias, String workItemId, String missionId, String runId,
                                    String interactionId, String title, String messageSummary, Instant createdAt,
                                    int attempt, Instant leaseExpiresAt) {}
    public record NotificationDeliveryEvent(
            @NotBlank @Pattern(regexp=IDENTIFIER) String deliveryEventId,
            @NotBlank @Pattern(regexp=NOTIFICATION_ID) String notificationId,
            @NotBlank @Pattern(regexp="DELIVERED|FAILED") String outcome,
            @Size(min=1,max=800) String failureSummary,
            @NotNull Instant reportedAt) {}
    public record NotificationDeliveryAck(String notificationId, String status, boolean duplicate) {}
    public record NodeSummary(String nodeId, String displayName, String status, List<String> capabilities,
                              String currentRunId, Instant lastHeartbeatAt, Instant lastSyncedAt) {}
}
