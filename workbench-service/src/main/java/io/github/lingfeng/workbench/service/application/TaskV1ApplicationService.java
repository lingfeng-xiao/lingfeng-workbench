package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.AcceptTaskRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.ContextRef;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.CreateTaskRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.CreatedTask;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.StartedTask;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskActionRequest;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskDetail;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskEvent;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskRun;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.TaskSummary;
import io.github.lingfeng.workbench.service.api.task.v1.TaskV1Dtos.UpdateTaskRequest;
import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator.MissionContract;
import io.github.lingfeng.workbench.service.persistence.TaskRepository;
import io.github.lingfeng.workbench.service.persistence.TaskRepository.IdempotencyRecord;
import io.github.lingfeng.workbench.service.persistence.TaskRepository.TaskEventRecord;
import io.github.lingfeng.workbench.service.persistence.TaskRepository.TaskRecord;
import io.github.lingfeng.workbench.service.persistence.TaskRepository.TaskRunRecord;
import io.github.lingfeng.workbench.service.persistence.V2Repository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskV1ApplicationService {
  private static final List<String> ACTIVE_RUN_STATES =
      List.of("assigned", "running", "waiting_interaction", "cancelling");

  private final TaskRepository taskRepository;
  private final V2Repository v2Repository;
  private final ClientV2ApplicationService clientV2Service;
  private final MissionDigestCalculator digestCalculator;
  private final V2ProtocolSupport protocol;
  private final ObjectMapper objectMapper;
  private final WorkbenchProperties properties;
  private final Clock clock = Clock.systemUTC();

  public TaskV1ApplicationService(
      TaskRepository taskRepository,
      V2Repository v2Repository,
      ClientV2ApplicationService clientV2Service,
      MissionDigestCalculator digestCalculator,
      ObjectMapper objectMapper,
      WorkbenchProperties properties) {
    this.taskRepository = taskRepository;
    this.v2Repository = v2Repository;
    this.clientV2Service = clientV2Service;
    this.digestCalculator = digestCalculator;
    this.objectMapper = objectMapper;
    this.protocol = new V2ProtocolSupport(objectMapper);
    this.properties = properties;
  }

  @Transactional
  public CreatedTask create(String principal, String idempotencyKey, CreateTaskRequest request) {
    validateContextRefs(request.contextRefs());
    String operation = "CREATE_TASK";
    String requestHash = protocol.hash(request);
    IdempotencyRecord replay = taskRepository.findIdempotency(principal, idempotencyKey).orElse(null);
    if (replay != null) {
      return replay(replay, operation, requestHash, CreatedTask.class);
    }
    Instant now = clock.instant();
    String taskId = protocol.id("task_");
    String contextRefsJson = protocol.json(request.contextRefs());
    taskRepository.insertTask(
        new TaskRecord(
            taskId,
            request.title(),
            request.objective(),
            request.acceptanceSummary(),
            request.sideEffectSummary(),
            request.effectivePriority(),
            request.targetNodeId(),
            request.workspaceRef(),
            contextRefsJson,
            request.runtimeKind(),
            request.executionProfile(),
            "DRAFT",
            "NOT_REQUESTED",
            "NONE",
            null,
            null,
            null,
            1,
            null,
            request.actor(),
            request.actor(),
            now,
            now,
            null));
    appendUserEvent(taskId, "TASK_CREATED", request.reason(), request.actor(), null, now);
    CreatedTask response = new CreatedTask(taskId, 1, "DRAFT", now);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail update(
      String principal, String idempotencyKey, String taskId, UpdateTaskRequest request) {
    validateContextRefs(request.contextRefs());
    String operation = "UPDATE_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord current = requireTask(taskId);
    Instant now = clock.instant();
    int changed =
        taskRepository.updateDefinition(
            taskId,
            request.expectedVersion(),
            request.title(),
            request.objective(),
            request.acceptanceSummary(),
            request.sideEffectSummary(),
            request.priority(),
            request.targetNodeId(),
            request.workspaceRef(),
            protocol.json(request.contextRefs()),
            request.runtimeKind(),
            request.executionProfile(),
            request.actor(),
            now);
    requireChanged(changed, current, request.expectedVersion(), "edit");
    appendUserEvent(taskId, "TASK_EDITED", request.reason(), request.actor(), null, now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail markReady(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    return stateAction(
        principal,
        idempotencyKey,
        taskId,
        request,
        "MARK_READY",
        "DRAFT",
        "READY",
        null,
        "NONE",
        "TASK_READY");
  }

  @Transactional
  public StartedTask start(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    String operation = "START_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    StartedTask replay =
        replayIfPresent(principal, idempotencyKey, operation, requestHash, StartedTask.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    requireVersionAndStatus(task, request.expectedVersion(), "READY", "start");
    if (taskRepository.findActiveRun(taskId).isPresent()) {
      throw DomainException.conflict("Task already has an active Run");
    }
    Instant now = clock.instant();
    int missionRevision = taskRepository.nextMissionRevision(taskId);
    String workItemId = protocol.id("wi_");
    String missionId = protocol.id("mi_");
    String runId = protocol.id("run_");
    String commandId = protocol.id("cmd_");
    List<String> contextRefs = contextRefs(task).stream().map(ContextRef::ref).toList();
    String digest =
        digestCalculator.calculate(
            new MissionContract(
                task.objective(),
                task.acceptanceSummary(),
                task.sideEffectSummary(),
                task.targetNodeId(),
                task.workspaceRef(),
                task.runtimeKind(),
                task.executionProfile(),
                missionRevision),
            contextRefs);
    v2Repository.insertAggregate(
        workItemId,
        task.title(),
        task.priority(),
        missionId,
        digest,
        task.objective(),
        task.acceptanceSummary(),
        task.sideEffectSummary(),
        task.targetNodeId(),
        task.workspaceRef(),
        task.runtimeKind(),
        task.executionProfile(),
        missionRevision,
        runId,
        commandId,
        now);
    String commandJson =
        startCommandJson(task, workItemId, missionId, runId, commandId, digest, missionRevision, contextRefs, now);
    v2Repository.insertCommand(
        commandId,
        task.targetNodeId(),
        workItemId,
        missionId,
        runId,
        digest,
        "START_RUN",
        commandJson,
        protocol.hashJson(commandJson),
        null,
        now);
    taskRepository.linkWorkItem(
        taskId, workItemId, task.version(), missionId, task.contextRefsJson(), now);
    int changed =
        taskRepository.updateState(
            taskId,
            request.expectedVersion(),
            "READY",
            "IN_PROGRESS",
            null,
            "NONE",
            request.actor(),
            now);
    requireChanged(changed, task, request.expectedVersion(), "start");
    appendUserEvent(
        taskId,
        "TASK_STARTED",
        request.reason(),
        request.actor(),
        new ExecutionBinding(workItemId, missionId, runId),
        now);
    StartedTask response =
        new StartedTask(
            taskId,
            task.version() + 1,
            workItemId,
            missionId,
            runId,
            "IN_PROGRESS",
            now);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail accept(
      String principal, String idempotencyKey, String taskId, AcceptTaskRequest request) {
    String operation = "ACCEPT_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    Instant now = clock.instant();
    int changed =
        taskRepository.accept(
            taskId,
            request.expectedVersion(),
            request.deliverySummary(),
            request.commitSha().toLowerCase(),
            request.prUrl(),
            request.actor(),
            now);
    requireChanged(changed, task, request.expectedVersion(), "accept");
    appendUserEvent(taskId, "TASK_ACCEPTED", request.reason(), request.actor(), null, now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail requestChanges(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    return stateAction(
        principal,
        idempotencyKey,
        taskId,
        request,
        "REQUEST_CHANGES",
        "REVIEW",
        "READY",
        "CHANGES_REQUESTED",
        "NONE",
        "TASK_CHANGES_REQUESTED");
  }

  @Transactional
  public TaskDetail cancel(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    String operation = "CANCEL_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    requireVersion(task, request.expectedVersion());
    if (!List.of("DRAFT", "READY", "IN_PROGRESS", "REVIEW").contains(task.businessStatus())) {
      throw conflict(task, "cancel");
    }
    TaskRunRecord activeRun = taskRepository.findActiveRun(taskId).orElse(null);
    if (activeRun != null) {
      clientV2Service.requestCancellation(activeRun.runId(), request.reason());
    }
    Instant now = clock.instant();
    int changed =
        taskRepository.updateState(
            taskId,
            request.expectedVersion(),
            task.businessStatus(),
            "CANCELLED",
            null,
            "NONE",
            request.actor(),
            now);
    requireChanged(changed, task, request.expectedVersion(), "cancel");
    appendUserEvent(
        taskId,
        "TASK_CANCELLED",
        request.reason(),
        request.actor(),
        activeRun == null
            ? null
            : new ExecutionBinding(activeRun.workItemId(), activeRun.missionId(), activeRun.runId()),
        now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail archive(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    String operation = "ARCHIVE_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    requireVersion(task, request.expectedVersion());
    if (!List.of("DONE", "CANCELLED").contains(task.businessStatus())) {
      throw conflict(task, "archive");
    }
    Instant now = clock.instant();
    int changed =
        taskRepository.archive(
            taskId, request.expectedVersion(), task.businessStatus(), request.actor(), now);
    requireChanged(changed, task, request.expectedVersion(), "archive");
    appendUserEvent(taskId, "TASK_ARCHIVED", request.reason(), request.actor(), null, now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional
  public TaskDetail restore(
      String principal, String idempotencyKey, String taskId, TaskActionRequest request) {
    String operation = "RESTORE_TASK:" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    Instant now = clock.instant();
    int changed =
        taskRepository.restore(taskId, request.expectedVersion(), request.actor(), now);
    requireChanged(changed, task, request.expectedVersion(), "restore");
    appendUserEvent(taskId, "TASK_RESTORED", request.reason(), request.actor(), null, now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  @Transactional(readOnly = true)
  public List<TaskSummary> list(
      String businessStatus,
      String attentionState,
      String targetNodeId,
      boolean includeArchived,
      int limit) {
    return taskRepository
        .listTasks(businessStatus, attentionState, targetNodeId, includeArchived, limit)
        .stream()
        .map(this::summary)
        .toList();
  }

  @Transactional(readOnly = true)
  public TaskDetail detail(String taskId) {
    TaskRecord task = requireTask(taskId);
    List<TaskRun> runs = taskRepository.listRuns(taskId).stream().map(this::taskRun).toList();
    NodeState node = nodeState(task.targetNodeId());
    String attention = effectiveAttention(task, runs, node);
    return new TaskDetail(
        task.taskId(),
        task.title(),
        task.objective(),
        task.acceptanceSummary(),
        task.sideEffectSummary(),
        task.priority(),
        task.targetNodeId(),
        task.workspaceRef(),
        contextRefs(task),
        task.runtimeKind(),
        task.executionProfile(),
        task.businessStatus(),
        task.acceptanceStatus(),
        attention,
        task.deliverySummary(),
        task.commitSha(),
        task.prUrl(),
        task.version(),
        allowedActions(task),
        node.status(),
        node.lastHeartbeatAt(),
        runs,
        events(taskId, 0, 100),
        task.createdAt(),
        task.updatedAt(),
        task.archivedAt());
  }

  @Transactional(readOnly = true)
  public List<TaskEvent> events(String taskId, long afterSequence, int limit) {
    requireTask(taskId);
    return taskRepository.listEvents(taskId, afterSequence, limit).stream()
        .map(this::taskEvent)
        .toList();
  }

  private TaskDetail stateAction(
      String principal,
      String idempotencyKey,
      String taskId,
      TaskActionRequest request,
      String operationName,
      String expectedState,
      String nextState,
      String acceptanceStatus,
      String attentionState,
      String eventType) {
    String operation = operationName + ":" + taskId;
    String requestHash = protocol.hash(request);
    TaskDetail replay = replayIfPresent(principal, idempotencyKey, operation, requestHash, TaskDetail.class);
    if (replay != null) {
      return replay;
    }
    TaskRecord task = requireTask(taskId);
    Instant now = clock.instant();
    int changed =
        taskRepository.updateState(
            taskId,
            request.expectedVersion(),
            expectedState,
            nextState,
            acceptanceStatus,
            attentionState,
            request.actor(),
            now);
    requireChanged(changed, task, request.expectedVersion(), operationName.toLowerCase());
    appendUserEvent(taskId, eventType, request.reason(), request.actor(), null, now);
    TaskDetail response = detail(taskId);
    saveReplay(principal, idempotencyKey, operation, requestHash, response, now);
    return response;
  }

  private String startCommandJson(
      TaskRecord task,
      String workItemId,
      String missionId,
      String runId,
      String commandId,
      String digest,
      int missionRevision,
      List<String> contextRefs,
      Instant now) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("protocolVersion", "2.0");
    payload.put("messageId", commandId);
    payload.put("nodeId", task.targetNodeId());
    payload.put("sentAt", now);
    payload.put("workItemId", workItemId);
    payload.put("missionId", missionId);
    payload.put("runId", runId);
    payload.put("missionDigest", digest);
    payload.put("commandAvailable", true);
    payload.put("commandType", "START_RUN");
    payload.put("commandId", commandId);
    payload.put("targetNodeId", task.targetNodeId());
    payload.put("missionRevision", missionRevision);
    payload.put("objective", task.objective());
    payload.put("acceptanceSummary", task.acceptanceSummary());
    payload.put("authorizedSideEffectsSummary", task.sideEffectSummary());
    payload.put("workspaceRef", task.workspaceRef());
    payload.put("contextRefs", contextRefs);
    payload.put("runtimeKind", task.runtimeKind());
    payload.put("executionProfile", task.executionProfile());
    return protocol.json(payload);
  }

  private TaskSummary summary(TaskRecord task) {
    TaskRunRecord latest = taskRepository.listRuns(task.taskId()).stream().findFirst().orElse(null);
    TaskRun run = latest == null ? null : taskRun(latest);
    NodeState node = nodeState(task.targetNodeId());
    List<TaskRun> runs = run == null ? List.of() : List.of(run);
    return new TaskSummary(
        task.taskId(),
        task.title(),
        task.priority(),
        task.targetNodeId(),
        task.businessStatus(),
        task.acceptanceStatus(),
        effectiveAttention(task, runs, node),
        task.version(),
        run == null ? null : run.status(),
        run == null ? null : run.progressSummary(),
        run == null ? null : run.lastObservedAt(),
        run != null && run.stale(),
        node.status(),
        task.updatedAt());
  }

  private TaskRun taskRun(TaskRunRecord run) {
    boolean stale =
        ACTIVE_RUN_STATES.contains(run.status())
            && (run.lastObservedAt() == null
                || run.lastObservedAt()
                    .isBefore(clock.instant().minus(properties.task().observationStaleAfter())));
    return new TaskRun(
        run.workItemId(),
        run.missionId(),
        run.runId(),
        run.missionRevision(),
        run.status(),
        run.phaseCode(),
        run.progressSummary(),
        run.resultSummary(),
        run.lastObservedAt(),
        stale,
        run.createdAt());
  }

  private TaskEvent taskEvent(TaskEventRecord event) {
    return new TaskEvent(
        event.eventId(),
        event.sequence(),
        event.eventType(),
        event.summary(),
        event.actor(),
        event.source(),
        event.workItemId(),
        event.missionId(),
        event.runId(),
        event.occurredAt());
  }

  private String effectiveAttention(TaskRecord task, List<TaskRun> runs, NodeState node) {
    if ("IN_PROGRESS".equals(task.businessStatus()) && "offline".equals(node.status())) {
      return "NODE_OFFLINE";
    }
    if (runs.stream().anyMatch(TaskRun::stale)) {
      return "STALE";
    }
    return task.attentionState();
  }

  private NodeState nodeState(String nodeId) {
    return taskRepository
        .findNode(nodeId)
        .map(
            node ->
                new NodeState(
                    node.lastHeartbeatAt()
                            .isBefore(clock.instant().minus(properties.node().offlineAfter()))
                        ? "offline"
                        : "online",
                    node.lastHeartbeatAt()))
        .orElseGet(() -> new NodeState("offline", null));
  }

  private List<String> allowedActions(TaskRecord task) {
    return switch (task.businessStatus()) {
      case "DRAFT" -> List.of("EDIT", "MARK_READY", "CANCEL");
      case "READY" -> List.of("EDIT", "START", "CANCEL");
      case "IN_PROGRESS" -> List.of("CANCEL");
      case "REVIEW" -> List.of("ACCEPT", "REQUEST_CHANGES", "CANCEL");
      case "DONE", "CANCELLED" -> List.of("ARCHIVE");
      case "ARCHIVED" -> List.of("RESTORE");
      default -> List.of();
    };
  }

  private List<ContextRef> contextRefs(TaskRecord task) {
    try {
      return objectMapper.readValue(task.contextRefsJson(), new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Stored Task context references are invalid", exception);
    }
  }

  private void validateContextRefs(List<ContextRef> contextRefs) {
    if (new HashSet<>(contextRefs.stream().map(ContextRef::ref).toList()).size()
        != contextRefs.size()) {
      throw DomainException.rejected("contextRefs must be unique");
    }
  }

  private TaskRecord requireTask(String taskId) {
    return taskRepository
        .findTask(taskId)
        .orElseThrow(() -> DomainException.notFound("Task", taskId));
  }

  private void requireChanged(
      int changed, TaskRecord task, long expectedVersion, String action) {
    if (changed == 0) {
      requireVersion(task, expectedVersion);
      throw conflict(task, action);
    }
  }

  private void requireVersionAndStatus(
      TaskRecord task, long expectedVersion, String expectedStatus, String action) {
    requireVersion(task, expectedVersion);
    if (!expectedStatus.equals(task.businessStatus())) {
      throw conflict(task, action);
    }
  }

  private void requireVersion(TaskRecord task, long expectedVersion) {
    if (task.version() != expectedVersion) {
      throw DomainException.conflict(
          "Task version conflict; currentVersion="
              + task.version()
              + ", currentStatus="
              + task.businessStatus());
    }
  }

  private DomainException conflict(TaskRecord task, String action) {
    return DomainException.conflict(
        "Task cannot "
            + action
            + "; currentVersion="
            + task.version()
            + ", currentStatus="
            + task.businessStatus());
  }

  private void appendUserEvent(
      String taskId,
      String eventType,
      String summary,
      String actor,
      ExecutionBinding binding,
      Instant now) {
    taskRepository.appendEvent(
        new TaskEventRecord(
            protocol.id("tev_"),
            taskId,
            eventType,
            summary,
            actor,
            "USER",
            binding == null ? null : binding.workItemId(),
            binding == null ? null : binding.missionId(),
            binding == null ? null : binding.runId(),
            now));
  }

  private <T> T replayIfPresent(
      String principal,
      String idempotencyKey,
      String operation,
      String requestHash,
      Class<T> responseType) {
    IdempotencyRecord replay =
        taskRepository.findIdempotency(principal, idempotencyKey).orElse(null);
    return replay == null ? null : replay(replay, operation, requestHash, responseType);
  }

  private <T> T replay(
      IdempotencyRecord replay, String operation, String requestHash, Class<T> responseType) {
    if (!replay.operation().equals(operation) || !replay.requestHash().equals(requestHash)) {
      throw DomainException.conflict("Idempotency-Key was used for different content");
    }
    return protocol.read(replay.responseJson(), responseType);
  }

  private void saveReplay(
      String principal,
      String idempotencyKey,
      String operation,
      String requestHash,
      Object response,
      Instant now) {
    taskRepository.insertIdempotency(
        principal, idempotencyKey, operation, requestHash, protocol.json(response), now);
  }

  private record ExecutionBinding(String workItemId, String missionId, String runId) {}

  private record NodeState(String status, Instant lastHeartbeatAt) {}
}
