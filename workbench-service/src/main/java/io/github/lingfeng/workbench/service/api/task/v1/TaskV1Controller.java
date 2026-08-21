package io.github.lingfeng.workbench.service.api.task.v1;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.IDENTIFIER;
import static io.github.lingfeng.workbench.service.api.ValidationPatterns.TASK_ID;

import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.AcceptTaskRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.CreateTaskRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.CreatedTask;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.StartedTask;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskActionRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskDetail;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskEvent;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskSummary;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.UpdateTaskRequest;
import io.github.lingfeng.workbench.service.application.TaskV1ApplicationService;
import io.github.lingfeng.workbench.service.security.WorkbenchPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tasks/v1")
public class TaskV1Controller {
  private final TaskV1ApplicationService service;

  public TaskV1Controller(TaskV1ApplicationService service) {
    this.service = service;
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedTask create(
      Authentication authentication,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody CreateTaskRequest request) {
    return service.create(principalKind(authentication), idempotencyKey, request);
  }

  @GetMapping("/tasks")
  public List<TaskSummary> list(
      @RequestParam(required = false)
          @Pattern(regexp = "DRAFT|READY|IN_PROGRESS|REVIEW|DONE|ARCHIVED|CANCELLED")
          String businessStatus,
      @RequestParam(required = false)
          @Pattern(
              regexp =
                  "NONE|WAITING_INPUT|APPROVAL_REQUIRED|RUN_FAILED|RUN_UNCERTAIN|NODE_OFFLINE|STALE")
          String attentionState,
      @RequestParam(required = false) @Pattern(regexp = IDENTIFIER) String targetNodeId,
      @RequestParam(defaultValue = "false") boolean includeArchived,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return service.list(businessStatus, attentionState, targetNodeId, includeArchived, limit);
  }

  @GetMapping("/tasks/{taskId}")
  public ResponseEntity<TaskDetail> detail(
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    TaskDetail detail = service.detail(taskId);
    String etag = "\"" + detail.version() + "\"";
    if (etag.equals(ifNoneMatch) || ("W/" + etag).equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).body(detail);
  }

  @GetMapping("/tasks/{taskId}/events")
  public List<TaskEvent> events(
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
    return service.events(taskId, afterSequence, limit);
  }

  @PutMapping("/tasks/{taskId}")
  public TaskDetail update(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody UpdateTaskRequest request) {
    return service.update(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/mark-ready")
  public TaskDetail markReady(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.markReady(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/start")
  public StartedTask start(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.start(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/accept")
  public TaskDetail accept(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody AcceptTaskRequest request) {
    return service.accept(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/request-changes")
  public TaskDetail requestChanges(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.requestChanges(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/cancel")
  public TaskDetail cancel(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.cancel(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/archive")
  public TaskDetail archive(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.archive(principalKind(authentication), idempotencyKey, taskId, request);
  }

  @PostMapping("/tasks/{taskId}/restore")
  public TaskDetail restore(
      Authentication authentication,
      @PathVariable @Pattern(regexp = TASK_ID) String taskId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
      @Valid @RequestBody TaskActionRequest request) {
    return service.restore(principalKind(authentication), idempotencyKey, taskId, request);
  }

  private String principalKind(Authentication authentication) {
    return ((WorkbenchPrincipal) authentication.getPrincipal()).kind().name();
  }
}
