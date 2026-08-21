package io.github.lingfeng.workbench.service.api.task.v1;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.IDENTIFIER;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class TaskV1Dtos {
  private TaskV1Dtos() {}

  public record ContextRef(
      @NotBlank @Pattern(regexp = IDENTIFIER) String ref,
      @NotBlank @Size(max = 200) String label) {}

  public record CreateTaskRequest(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 800) String objective,
      @NotBlank @Size(max = 800) String acceptanceSummary,
      @NotBlank @Size(max = 800) String sideEffectSummary,
      @Min(-100) @Max(100) Integer priority,
      @NotBlank @Pattern(regexp = IDENTIFIER) String targetNodeId,
      @NotBlank @Pattern(regexp = IDENTIFIER) String workspaceRef,
      @NotEmpty @Size(max = 16) List<@Valid ContextRef> contextRefs,
      @NotBlank @Pattern(regexp = IDENTIFIER) String runtimeKind,
      @NotBlank @Pattern(regexp = IDENTIFIER) String executionProfile,
      @NotNull @AssertTrue Boolean dataBoundaryAcknowledged,
      @NotBlank @Pattern(regexp = IDENTIFIER) String actor,
      @NotBlank @Size(max = 800) String reason) {
    public int effectivePriority() {
      return priority == null ? 0 : priority;
    }
  }

  public record UpdateTaskRequest(
      @Min(1) long expectedVersion,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 800) String objective,
      @NotBlank @Size(max = 800) String acceptanceSummary,
      @NotBlank @Size(max = 800) String sideEffectSummary,
      @Min(-100) @Max(100) int priority,
      @NotBlank @Pattern(regexp = IDENTIFIER) String targetNodeId,
      @NotBlank @Pattern(regexp = IDENTIFIER) String workspaceRef,
      @NotEmpty @Size(max = 16) List<@Valid ContextRef> contextRefs,
      @NotBlank @Pattern(regexp = IDENTIFIER) String runtimeKind,
      @NotBlank @Pattern(regexp = IDENTIFIER) String executionProfile,
      @NotBlank @Pattern(regexp = IDENTIFIER) String actor,
      @NotBlank @Size(max = 800) String reason) {}

  public record TaskActionRequest(
      @Min(1) long expectedVersion,
      @NotBlank @Pattern(regexp = IDENTIFIER) String actor,
      @NotBlank @Size(max = 800) String reason) {}

  public record AcceptTaskRequest(
      @Min(1) long expectedVersion,
      @NotBlank @Pattern(regexp = IDENTIFIER) String actor,
      @NotBlank @Size(max = 800) String reason,
      @NotBlank @Size(max = 800) String deliverySummary,
      @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{7,64}$") String commitSha,
      @NotBlank @Size(max = 800) @Pattern(regexp = "^https://[^\\s]+$") String prUrl) {}

  public record TaskSummary(
      String taskId,
      String title,
      int priority,
      String targetNodeId,
      String businessStatus,
      String acceptanceStatus,
      String attentionState,
      long version,
      String runStatus,
      String progressSummary,
      Instant lastObservedAt,
      boolean stale,
      String nodeStatus,
      Instant updatedAt) {}

  public record TaskRun(
      String workItemId,
      String missionId,
      String runId,
      int missionRevision,
      String status,
      String phaseCode,
      String progressSummary,
      String resultSummary,
      Instant lastObservedAt,
      boolean stale,
      Instant createdAt) {}

  public record TaskEvent(
      String eventId,
      long sequence,
      String eventType,
      String summary,
      String actor,
      String source,
      String workItemId,
      String missionId,
      String runId,
      Instant occurredAt) {}

  public record TaskDetail(
      String taskId,
      String title,
      String objective,
      String acceptanceSummary,
      String sideEffectSummary,
      int priority,
      String targetNodeId,
      String workspaceRef,
      List<ContextRef> contextRefs,
      String runtimeKind,
      String executionProfile,
      String businessStatus,
      String acceptanceStatus,
      String attentionState,
      String deliverySummary,
      String commitSha,
      String prUrl,
      long version,
      List<String> allowedActions,
      String nodeStatus,
      Instant nodeLastHeartbeatAt,
      List<TaskRun> runs,
      List<TaskEvent> timeline,
      Instant createdAt,
      Instant updatedAt,
      Instant archivedAt) {}

  public record CreatedTask(String taskId, long version, String businessStatus, Instant createdAt) {}

  public record StartedTask(
      String taskId,
      long version,
      String workItemId,
      String missionId,
      String runId,
      String businessStatus,
      Instant startedAt) {}
}
