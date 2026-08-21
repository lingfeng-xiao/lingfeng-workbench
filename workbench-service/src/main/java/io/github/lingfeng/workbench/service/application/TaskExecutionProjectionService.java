package io.github.lingfeng.workbench.service.application;

import io.github.lingfeng.workbench.service.api.node.v2.NodeV2Dtos.NodeEvent;
import io.github.lingfeng.workbench.service.persistence.TaskRepository;
import io.github.lingfeng.workbench.service.persistence.TaskRepository.TaskEventRecord;
import io.github.lingfeng.workbench.service.persistence.V2Repository.Binding;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionProjectionService {
  private final TaskRepository taskRepository;

  public TaskExecutionProjectionService(TaskRepository taskRepository) {
    this.taskRepository = taskRepository;
  }

  public void project(NodeEvent event, Binding binding, Instant now) {
    String taskId = taskRepository.findTaskIdByWorkItem(binding.workItemId()).orElse(null);
    if (taskId == null) {
      return;
    }
    switch (event.eventType()) {
      case "RUN_STARTED" -> taskRepository.projectRunStarted(taskId, now);
      case "INTERACTION_REQUESTED" ->
          taskRepository.touchExecutionProjection(taskId, "WAITING_INPUT", now);
      case "INTERACTION_RESPONSE_CONSUMED" ->
          taskRepository.touchExecutionProjection(taskId, "NONE", now);
      case "RUN_TERMINAL" -> projectTerminal(taskId, event, now);
      default -> taskRepository.touchExecutionProjection(taskId, null, now);
    }
    taskRepository.appendEvent(
        new TaskEventRecord(
            taskEventId(event),
            taskId,
            taskEventType(event.eventType()),
            summary(event),
            event.nodeId(),
            "NODE",
            binding.workItemId(),
            binding.missionId(),
            binding.runId(),
            now));
  }

  private void projectTerminal(String taskId, NodeEvent event, Instant now) {
    boolean completed =
        "SUCCEEDED".equals(event.runtimeOutcome()) && "PASSED".equals(event.acceptanceStatus());
    String attentionState =
        "UNKNOWN".equals(event.runtimeOutcome()) || "UNKNOWN".equals(event.acceptanceStatus())
            ? "RUN_UNCERTAIN"
            : "RUN_FAILED";
    taskRepository.projectRunTerminal(taskId, completed, attentionState, now);
  }

  private String taskEventId(NodeEvent event) {
    return "tev_" + event.nodeId() + "_" + event.messageId();
  }

  private String taskEventType(String eventType) {
    return switch (eventType) {
      case "COMMAND_STORED" -> "NODE_COMMAND_STORED";
      case "RUN_STARTED" -> "RUN_STARTED";
      case "PHASE_CHANGED" -> "RUN_PHASE_CHANGED";
      case "PROGRESS_UPDATED" -> "RUN_PROGRESS_UPDATED";
      case "INTERACTION_REQUESTED" -> "RUN_WAITING";
      case "INTERACTION_RESPONSE_CONSUMED" -> "RUN_RESUMED";
      case "RUN_TERMINAL" -> "RUN_TERMINAL";
      default -> eventType;
    };
  }

  private String summary(NodeEvent event) {
    if (event.resultSummary() != null) {
      return event.resultSummary();
    }
    if (event.progressSummary() != null) {
      return event.progressSummary();
    }
    if (event.phaseSummary() != null) {
      return event.phaseSummary();
    }
    if (event.promptSummary() != null) {
      return event.promptSummary();
    }
    return event.eventType();
  }
}
