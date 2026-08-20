package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.node.v2.NodeV2Dtos.*;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.persistence.V2Repository;
import io.github.lingfeng.workbench.service.persistence.V2Repository.*;
import io.github.lingfeng.workbench.service.persistence.NodeRegistryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeV2ApplicationService {
  private final V2Repository repository;
  private final NodeRegistryRepository nodeRegistry;
  private final ObjectMapper mapper;
  private final V2ProtocolSupport protocol;
  private final Clock clock = Clock.systemUTC();

  public NodeV2ApplicationService(
      V2Repository repository, NodeRegistryRepository nodeRegistry, ObjectMapper mapper) {
    this.repository = repository;
    this.nodeRegistry = nodeRegistry;
    this.mapper = mapper;
    this.protocol = new V2ProtocolSupport(mapper);
  }

  @Transactional
  public Acknowledgement hello(HelloRequest request) {
    if (new HashSet<>(request.capabilities()).size() != request.capabilities().size())
      throw DomainException.rejected("capabilities must be unique");
    Replay replay = replay(request.nodeId(), request.messageId(), "HELLO", request);
    if (replay.duplicate()) return new Acknowledgement(request.messageId(), true);
    Instant now = clock.instant();
    nodeRegistry.upsertNode(
        request.nodeId(), request.displayName(), request.capabilities(), now);
    repository.insertEvent(request.nodeId(), request.messageId(), "HELLO", replay.hash(), now);
    return new Acknowledgement(request.messageId(), false);
  }

  @Transactional
  public Acknowledgement heartbeat(HeartbeatRequest request) {
    Replay replay = replay(request.nodeId(), request.messageId(), "HEARTBEAT", request);
    if (replay.duplicate()) return new Acknowledgement(request.messageId(), true);
    if (request.activeRunId() != null) {
      Binding b =
          repository
              .findBinding(request.activeRunId())
              .orElseThrow(() -> DomainException.rejected("activeRunId does not exist"));
      if (!b.nodeId().equals(request.nodeId()))
        throw DomainException.conflict("activeRunId belongs to another Node");
      if (request.activeRunState() != null && terminal(b.runStatus()))
        throw DomainException.conflict("Terminal Run cannot be reported active");
    }
    Instant now = clock.instant();
    if (!nodeRegistry.touchHeartbeat(request.nodeId(), now))
      throw DomainException.rejected("Node must call hello before heartbeat");
    repository.insertEvent(request.nodeId(), request.messageId(), "HEARTBEAT", replay.hash(), now);
    return new Acknowledgement(request.messageId(), false);
  }

  @Transactional(readOnly = true)
  public Object poll(PollRequest request) {
    if (!nodeRegistry.exists(request.nodeId()))
      throw DomainException.rejected("Node must call hello before polling");
    Command command = repository.pollCommand(request.nodeId()).orElse(null);
    if (command == null) return new NoCommand();
    try {
      return mapper.readTree(command.payloadJson());
    } catch (Exception exception) {
      throw new IllegalStateException("Durable command payload is invalid", exception);
    }
  }

  @Transactional
  public Acknowledgement event(NodeEvent event) {
    validateShape(event);
    Replay replay = replay(event.nodeId(), event.messageId(), event.eventType(), event);
    if (replay.duplicate()) return new Acknowledgement(event.messageId(), true);
    Binding b =
        repository
            .findBinding(event.runId())
            .orElseThrow(() -> DomainException.rejected("runId does not exist"));
    validateBinding(event, b);
    Instant now = clock.instant();
    apply(event, b, now);
    repository.insertEvent(
        event.nodeId(), event.messageId(), event.eventType(), replay.hash(), now);
    repository.insertTimeline(
        event.messageId(), event.runId(), event.eventType(), summary(event), now);
    return new Acknowledgement(event.messageId(), false);
  }

  private void apply(NodeEvent e, Binding b, Instant now) {
    switch (e.eventType()) {
      case "COMMAND_STORED" -> commandStored(e, b, now);
      case "RUN_STARTED" -> {
        requireState(b, List.of("assigned", "running"));
        repository.updateRun(b.runId(), "running", e.resumable(), null, null, null, now);
        repository.updateAggregateStatus(
            b.missionId(), b.workItemId(), "running", "in_progress", now);
      }
      case "PHASE_CHANGED" -> {
        requireState(b, List.of("running"));
        repository.updateRun(b.runId(), "running", null, e.phaseCode(), null, null, now);
      }
      case "PROGRESS_UPDATED" -> {
        requireState(b, List.of("running"));
        repository.updateRun(b.runId(), "running", null, null, e.progressSummary(), null, now);
      }
      case "INTERACTION_REQUESTED" -> interactionRequested(e, b, now);
      case "INTERACTION_RESPONSE_CONSUMED" -> interactionConsumed(e, b, now);
      case "RUN_TERMINAL" -> terminal(e, b, now);
      default -> throw DomainException.rejected("Unknown eventType");
    }
  }

  private void commandStored(NodeEvent event, Binding binding, Instant now) {
    Command command =
        repository
            .findCommand(event.commandId())
            .orElseThrow(() -> DomainException.conflict("commandId is unknown"));
    if (!command.nodeId().equals(event.nodeId())
        || !command.runId().equals(event.runId())
        || !command.payloadDigest().equals(event.commandPayloadDigest()))
      throw DomainException.conflict("Command ACK binding or payload digest does not match");
    repository.acknowledgeCommand(command.id(), now);
    if (command.interactionId() != null) {
      repository.deliverInteraction(command.interactionId());
    }
  }

  private void interactionRequested(NodeEvent e, Binding b, Instant now) {
    if (!e.nodeId().equals(e.targetNodeId()))
      throw DomainException.conflict("Interaction target Node does not match");
    Interaction old = repository.findInteraction(e.interactionId()).orElse(null);
    String decisions = protocol.json(e.allowedDecisions());
    if (old != null) {
      if (!old.runId().equals(e.runId())
          || !old.checkpointId().equals(e.checkpointId())
          || !old.digest().equals(e.missionDigest())
          || !old.nodeId().equals(e.nodeId()))
        throw DomainException.conflict("interactionId was used with another binding");
      return;
    }
    requireState(b, List.of("running"));
    repository.insertInteraction(
        new Interaction(
            e.interactionId(),
            e.runId(),
            e.checkpointId(),
            e.missionDigest(),
            e.nodeId(),
            "pending",
            e.promptSummary(),
            decisions,
            null,
            null,
            null,
            null,
            now));
    repository.updateRun(b.runId(), "waiting_interaction", true, null, null, null, now);
    repository.updateAggregateStatus(
        b.missionId(), b.workItemId(), "waiting_interaction", "attention_required", now);
    repository.insertNotification(
        new Notification(
            protocol.id("ntf_"),
            e.messageId() + ":INTERACTION_REQUIRED:owner",
            "INTERACTION_REQUIRED",
            b.workItemId(),
            b.missionId(),
            b.runId(),
            e.interactionId(),
            "Interaction required",
            e.promptSummary(),
            now));
  }

  private void interactionConsumed(NodeEvent e, Binding b, Instant now) {
    requireState(b, List.of("waiting_interaction"));
    Interaction i =
        repository
            .findInteraction(e.interactionId())
            .orElseThrow(() -> DomainException.conflict("Interaction is unknown"));
    if (!"delivered".equals(i.state())
        || !i.runId().equals(e.runId())
        || !i.checkpointId().equals(e.checkpointId())
        || !i.nodeId().equals(e.targetNodeId())
        || !e.responseCommandId().equals(i.responseCommandId()))
      throw DomainException.conflict("Interaction consumed binding or state does not match");
    repository.consumeInteraction(i.id(), now);
    repository.updateRun(b.runId(), "running", true, null, null, null, now);
    repository.updateAggregateStatus(b.missionId(), b.workItemId(), "running", "in_progress", now);
  }

  private void terminal(NodeEvent e, Binding b, Instant now) {
    if (terminal(b.runStatus())) throw DomainException.conflict("Run already has a terminal state");
    requireState(b, List.of("assigned", "running", "waiting_interaction", "cancelling"));
    if ("PASSED".equals(e.acceptanceStatus()) && !"SUCCEEDED".equals(e.runtimeOutcome())) {
      throw DomainException.conflict("PASSED requires a successful Runtime outcome");
    }
    String runStatus, missionStatus, type;
    if ("SUCCEEDED".equals(e.runtimeOutcome()) && "PASSED".equals(e.acceptanceStatus())) {
      runStatus = "completed";
      missionStatus = "completed";
      type = "RUN_COMPLETED";
    } else if ("UNKNOWN".equals(e.runtimeOutcome()) || "UNKNOWN".equals(e.acceptanceStatus())) {
      runStatus = "uncertain";
      missionStatus = "uncertain";
      type = "RUN_UNCERTAIN";
    } else if ("INTERRUPTED".equals(e.runtimeOutcome())) {
      runStatus = "interrupted";
      missionStatus = "interrupted";
      type = "RUN_FAILED";
    } else {
      runStatus = "failed";
      missionStatus = "failed";
      type = "RUN_FAILED";
    }
    repository.updateRun(b.runId(), runStatus, false, null, null, e.resultSummary(), now);
    repository.updateAggregateStatus(
        b.missionId(),
        b.workItemId(),
        missionStatus,
        "completed".equals(runStatus) ? "completed" : "attention_required",
        now);
    repository.insertNotification(
        new Notification(
            protocol.id("ntf_"),
            e.messageId() + ":" + type + ":owner",
            type,
            b.workItemId(),
            b.missionId(),
            b.runId(),
            null,
            "Run status changed",
            e.resultSummary(),
            now));
  }

  private void validateBinding(NodeEvent event, Binding binding) {
    if (!binding.nodeId().equals(event.nodeId())
        || !binding.workItemId().equals(event.workItemId())
        || !binding.missionId().equals(event.missionId())
        || !binding.digest().equals(event.missionDigest())) {
      throw DomainException.conflict("Run event binding does not match durable state");
    }
  }

  private Replay replay(String node, String message, String type, Object payload) {
    String hash = protocol.hash(payload);
    EventDedup old = repository.findEvent(node, message).orElse(null);
    if (old == null) return new Replay(false, hash);
    if (!old.eventType().equals(type) || !old.payloadHash().equals(hash))
      throw DomainException.conflict("messageId was used for different content");
    return new Replay(true, hash);
  }

  private void validateShape(NodeEvent event) {
    boolean valid =
        switch (event.eventType()) {
          case "COMMAND_STORED" ->
              present(event.commandId(), event.commandPayloadDigest())
                  && absent(
                      event.resumable(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.progressSummary(),
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.responseCommandId(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "RUN_STARTED" ->
              event.resumable() != null
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.progressSummary(),
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.responseCommandId(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "PHASE_CHANGED" ->
              present(event.phaseCode(), event.phaseSummary())
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.resumable(),
                      event.progressSummary(),
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.responseCommandId(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "PROGRESS_UPDATED" ->
              present(event.progressSummary())
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.resumable(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.responseCommandId(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "INTERACTION_REQUESTED" ->
              Boolean.TRUE.equals(event.resumable())
                  && present(
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions())
                  && unique(event.allowedDecisions())
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.progressSummary(),
                      event.responseCommandId(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "INTERACTION_RESPONSE_CONSUMED" ->
              present(
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.responseCommandId())
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.resumable(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.progressSummary(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.runtimeOutcome(),
                      event.acceptanceStatus(),
                      event.resultSummary());
          case "RUN_TERMINAL" ->
              Boolean.FALSE.equals(event.resumable())
                  && present(
                      event.runtimeOutcome(), event.acceptanceStatus(), event.resultSummary())
                  && absent(
                      event.commandId(),
                      event.commandPayloadDigest(),
                      event.phaseCode(),
                      event.phaseSummary(),
                      event.progressSummary(),
                      event.interactionId(),
                      event.checkpointId(),
                      event.targetNodeId(),
                      event.promptSummary(),
                      event.allowedDecisions(),
                      event.responseCommandId());
          default -> false;
        };
    if (!valid) throw DomainException.rejected("Event fields do not match eventType");
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

  private void requireState(Binding binding, List<String> allowedStates) {
    if (!allowedStates.contains(binding.runStatus())) {
      throw DomainException.conflict("Run state " + binding.runStatus() + " does not allow event");
    }
  }

  private boolean terminal(String state) {
    return List.of("completed", "failed", "interrupted", "uncertain", "cancelled").contains(state);
  }

  private boolean present(Object... values) {
    for (Object value : values) {
      if (value == null) {
        return false;
      }
    }
    return true;
  }

  private boolean absent(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return false;
      }
    }
    return true;
  }

  private boolean unique(List<String> values) {
    return values != null && new HashSet<>(values).size() == values.size();
  }

  private record Replay(boolean duplicate, String hash) {}
}
