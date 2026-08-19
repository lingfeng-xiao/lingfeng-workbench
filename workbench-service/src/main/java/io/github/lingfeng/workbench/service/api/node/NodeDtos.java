package io.github.lingfeng.workbench.service.api.node;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.DIGEST;
import static io.github.lingfeng.workbench.service.api.ValidationPatterns.IDENTIFIER;

import io.github.lingfeng.workbench.service.domain.Statuses.AcceptanceStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RuntimeOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class NodeDtos {
    private NodeDtos() {}

    public record HelloRequest(
            @NotBlank @Pattern(regexp = "1\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp = IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeId,
            @NotNull Instant sentAt,
            @NotBlank @Size(max = 800) String displayName,
            @NotNull @Size(max = 32) List<@NotBlank @Pattern(regexp = IDENTIFIER) String> capabilities) {}

    public record HeartbeatRequest(
            @NotBlank @Pattern(regexp = "1\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp = IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeId,
            @NotNull Instant sentAt,
            @Pattern(regexp = IDENTIFIER) String activeRunId) {}

    public record PollRequest(
            @NotBlank @Pattern(regexp = "1\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp = IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeId,
            @NotNull Instant sentAt,
            @Size(max = 100) List<@NotBlank @Pattern(regexp = IDENTIFIER) String> acknowledgedCommandIds) {
        public List<String> effectiveAcknowledgedCommandIds() {
            return acknowledgedCommandIds == null ? List.of() : List.copyOf(acknowledgedCommandIds);
        }
    }

    public record RunEvent(
            @NotBlank @Pattern(regexp = "1\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp = IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String nodeId,
            @NotNull Instant sentAt,
            @NotNull EventType eventType,
            @NotBlank @Pattern(regexp = IDENTIFIER) String workItemId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String missionId,
            @NotBlank @Pattern(regexp = IDENTIFIER) String runId,
            @NotBlank @Pattern(regexp = DIGEST) String missionDigest,
            @Pattern(regexp = IDENTIFIER) String commandId,
            Boolean resumable,
            @Size(min = 1, max = 800) String progressSummary,
            RuntimeOutcome runtimeOutcome,
            AcceptanceStatus acceptanceStatus,
            @Size(min = 1, max = 800) String resultSummary) {}

    public enum EventType { RUN_ACCEPTED, RUN_STARTED, PROGRESS, EXECUTION_FINISHED, EXECUTION_FAILED, EXECUTION_INTERRUPTED }

    public record Acknowledgement(String requestMessageId, boolean duplicate) {}

    public record NoCommand(String commandType) {
        public NoCommand() { this("NO_COMMAND"); }
    }

    public record AssignmentCommand(
            String commandType,
            String commandId,
            String workItemId,
            String missionId,
            String runId,
            int missionRevision,
            String missionDigest,
            String objective,
            String acceptanceSummary,
            String authorizedSideEffectsSummary,
            String targetNodeId,
            String workspaceRef,
            String runtimeKind,
            String executionProfile) {}
}
