package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.client.v2.ClientV2Dtos.*;
import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator.MissionContract;
import io.github.lingfeng.workbench.service.persistence.V2Repository;
import io.github.lingfeng.workbench.service.persistence.V2Repository.*;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientV2ApplicationService {
  private final V2Repository repository;
  private final MissionDigestCalculator digestCalculator;
  private final ObjectMapper mapper;
  private final V2ProtocolSupport protocol;
  private final WorkbenchProperties properties;
  private final Clock clock = Clock.systemUTC();

  public ClientV2ApplicationService(
      V2Repository repository,
      MissionDigestCalculator digestCalculator,
      ObjectMapper mapper,
      WorkbenchProperties properties) {
    this.repository = repository;
    this.digestCalculator = digestCalculator;
    this.mapper = mapper;
    this.protocol = new V2ProtocolSupport(mapper);
    this.properties = properties;
  }

  @Transactional
  public CreatedWorkItem create(String principal, String key, CreateWorkItemRequest request) {
    String hash = protocol.hash(request);
    var old = repository.findIdempotency(principal, key);
    if (old.isPresent()) return replay(old.get(), "CREATE_WORK_ITEM", hash, CreatedWorkItem.class);
    Instant now = clock.instant();
    String work = protocol.id("wi_"),
        mission = protocol.id("mi_"),
        run = protocol.id("run_"),
        command = protocol.id("cmd_");
    String digest =
        digestCalculator.calculate(
            new MissionContract(
                request.objective(),
                request.acceptanceSummary(),
                request.authorizedSideEffectsSummary(),
                request.targetNodeId(),
                request.workspaceRef(),
                request.runtimeKind(),
                request.executionProfile(),
                1));
    repository.insertAggregate(
        work,
        request.title(),
        request.effectivePriority(),
        mission,
        digest,
        request.objective(),
        request.acceptanceSummary(),
        request.authorizedSideEffectsSummary(),
        request.targetNodeId(),
        request.workspaceRef(),
        request.runtimeKind(),
        request.executionProfile(),
        1,
        run,
        command,
        now);
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    header(payload, command, request.targetNodeId(), now, work, mission, run, digest);
    payload.put("commandAvailable", true);
    payload.put("commandType", "START_RUN");
    payload.put("commandId", command);
    payload.put("targetNodeId", request.targetNodeId());
    payload.put("missionRevision", 1);
    payload.put("objective", request.objective());
    payload.put("acceptanceSummary", request.acceptanceSummary());
    payload.put("authorizedSideEffectsSummary", request.authorizedSideEffectsSummary());
    payload.put("workspaceRef", request.workspaceRef());
    payload.put("runtimeKind", request.runtimeKind());
    payload.put("executionProfile", request.executionProfile());
    String commandJson = protocol.json(payload);
    repository.insertCommand(
        command,
        request.targetNodeId(),
        work,
        mission,
        run,
        digest,
        "START_RUN",
        commandJson,
        protocol.hashJson(commandJson),
        null,
        now);
    CreatedWorkItem response = new CreatedWorkItem(work, mission, run, 1, digest, now);
    repository.insertIdempotency(
        principal, key, "CREATE_WORK_ITEM", hash, protocol.json(response), now);
    return response;
  }

  @Transactional
  public InteractionResolution resolve(
      String principal, String key, String pathId, ResolveInteractionRequest request) {
    if (!pathId.equals(request.interactionId()))
      throw DomainException.conflict("interactionId path does not match body");
    String hash = protocol.hash(request);
    var old = repository.findIdempotency(principal, key);
    if (old.isPresent())
      return replay(old.get(), "RESOLVE_INTERACTION", hash, InteractionResolution.class);
    Interaction i =
        repository
            .findInteraction(pathId)
            .orElseThrow(() -> DomainException.notFound("Interaction", pathId));
    Binding b =
        repository
            .findBinding(i.runId())
            .orElseThrow(() -> DomainException.conflict("Interaction Run is missing"));
    if (!i.runId().equals(request.runId())
        || !i.checkpointId().equals(request.checkpointId())
        || !i.digest().equals(request.missionDigest())
        || !b.nodeId().equals(i.nodeId()))
      throw DomainException.conflict("Interaction binding does not match durable state");
    if (!"pending".equals(i.state())) throw DomainException.conflict("Interaction is not pending");
    List<String> allowed = readList(i.allowedDecisionsJson());
    if (!allowed.contains(request.decision()))
      throw DomainException.conflict("Decision is not allowed");
    Instant now = clock.instant();
    String command = protocol.id("cmd_");
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    header(payload, command, b.nodeId(), now, b.workItemId(), b.missionId(), b.runId(), b.digest());
    payload.put("commandAvailable", true);
    payload.put("commandType", "PROVIDE_INTERACTION_RESPONSE");
    payload.put("commandId", command);
    payload.put("targetNodeId", b.nodeId());
    payload.put("interactionId", i.id());
    payload.put("checkpointId", i.checkpointId());
    payload.put("decision", request.decision());
    payload.put("responseSummary", request.responseSummary());
    payload.put("resolvedAt", request.resolvedAt());
    String json = protocol.json(payload);
    repository.insertCommand(
        command,
        b.nodeId(),
        b.workItemId(),
        b.missionId(),
        b.runId(),
        b.digest(),
        "PROVIDE_INTERACTION_RESPONSE",
        json,
        protocol.hashJson(json),
        i.id(),
        now);
    repository.resolveInteraction(i.id(), request.responseSummary(), request.resolvedAt(), command);
    repository.insertTimeline(
        command, b.runId(), "INTERACTION_RESOLVED", "Interaction response recorded", now);
    InteractionResolution response =
        new InteractionResolution(i.id(), "resolved", command, false, request.resolvedAt());
    repository.insertIdempotency(
        principal, key, "RESOLVE_INTERACTION", hash, protocol.json(response), now);
    return response;
  }

  @Transactional
  public Object pollNotification(NotificationPollRequest request) {
    Instant now = clock.instant();
    return repository
        .leaseNotification(
            request.targetAlias(), now, now.plus(properties.notification().leaseDuration()))
        .<Object>map(
            n ->
                new NotificationLease(
                    true,
                    n.id(),
                    n.type(),
                    "owner",
                    n.workItemId(),
                    n.missionId(),
                    n.runId(),
                    n.interactionId(),
                    n.title(),
                    n.summary(),
                    n.createdAt(),
                    n.attempt(),
                    n.leaseExpiresAt()))
        .orElseGet(NoNotification::new);
  }

  @Transactional
  public NotificationDeliveryAck report(
      String principal, String key, String pathId, NotificationDeliveryEvent event) {
    if (!pathId.equals(event.notificationId()))
      throw DomainException.conflict("notificationId path does not match body");
    String hash = protocol.hash(event);
    var old = repository.findIdempotency(principal, key);
    if (old.isPresent())
      return replay(old.get(), "REPORT_NOTIFICATION", hash, NotificationDeliveryAck.class);
    Notification n =
        repository
            .findNotification(pathId)
            .orElseThrow(() -> DomainException.notFound("Notification", pathId));
    var existingDelivery = repository.findDeliveryHash(pathId, event.deliveryEventId());
    if (existingDelivery.isPresent()) {
      if (!existingDelivery.get().equals(hash))
        throw DomainException.conflict("deliveryEventId was used for different content");
      NotificationDeliveryAck response = new NotificationDeliveryAck(pathId, n.status(), true);
      repository.insertIdempotency(
          principal, key, "REPORT_NOTIFICATION", hash, protocol.json(response), clock.instant());
      return response;
    }
    if ("delivered".equals(n.status()) && !"DELIVERED".equals(event.outcome()))
      throw DomainException.conflict("Delivered notification is terminal");
    if (!List.of("leased", "delivered").contains(n.status()))
      throw DomainException.conflict("Notification must be leased before delivery is reported");
    if (("FAILED".equals(event.outcome())) != (event.failureSummary() != null))
      throw DomainException.rejected("FAILED requires failureSummary and DELIVERED forbids it");
    repository.reportDelivery(
        pathId,
        event.deliveryEventId(),
        hash,
        event.outcome(),
        event.reportedAt(),
        properties.notification().maxAttempts());
    String status = repository.findNotification(pathId).orElseThrow().status();
    NotificationDeliveryAck response = new NotificationDeliveryAck(pathId, status, false);
    repository.insertIdempotency(
        principal, key, "REPORT_NOTIFICATION", hash, protocol.json(response), clock.instant());
    return response;
  }

  @Transactional(readOnly = true)
  public List<WorkItemSummary> list(int limit) {
    return repository.listSummaries(limit).stream().map(this::summary).toList();
  }

  @Transactional(readOnly = true)
  public WorkItemDetail detail(String id) {
    Binding b =
        repository
            .findWorkItemBinding(id)
            .orElseThrow(() -> DomainException.notFound("WorkItem", id));
    List<InteractionSummary> interactions =
        repository.listInteractions(null, 100).stream()
            .filter(i -> i.runId().equals(b.runId()))
            .map(this::interaction)
            .toList();
    List<NotificationProjection> notifications =
        repository.notificationsForWorkItem(id).stream()
            .map(n -> new NotificationProjection(n.id(), n.type(), n.status(), n.createdAt()))
            .toList();
    List<TimelineEvent> timeline =
        repository.timeline(b.runId()).stream()
            .map(
                m ->
                    new TimelineEvent(
                        (String) m.get("event_id"),
                        (String) m.get("event_type"),
                        (String) m.get("summary"),
                        Instant.parse((String) m.get("created_at"))))
            .toList();
    return new WorkItemDetail(
        id,
        b.title(),
        b.workStatus(),
        b.priority(),
        new MissionProjection(
            b.missionId(), b.revision(), b.objective(), b.acceptance(), b.missionStatus()),
        new RunProjection(
            b.runId(),
            b.nodeId(),
            b.runStatus(),
            b.phase(),
            b.progress(),
            b.result(),
            b.resumable(),
            b.lastSyncedAt()),
        interactions,
        notifications,
        timeline,
        b.updatedAt());
  }

  @Transactional(readOnly = true)
  public List<InteractionSummary> interactions(String state, int limit) {
    return repository.listInteractions(state, limit).stream().map(this::interaction).toList();
  }

  @Transactional(readOnly = true)
  public List<NodeSummary> nodes() {
    Instant offlineBefore = clock.instant().minus(properties.node().offlineAfter());
    return repository.listNodeSummaries().stream()
        .map(
            m -> {
              Instant heartbeat = Instant.parse((String) m.get("last_heartbeat_at"));
              return new NodeSummary(
                  (String) m.get("node_id"),
                  (String) m.get("display_name"),
                  heartbeat.isBefore(offlineBefore) ? "offline" : "online",
                  readList((String) m.get("capabilities_json")),
                  (String) m.get("current_run_id"),
                  heartbeat,
                  instant(m.get("last_synced_at")) == null
                      ? heartbeat
                      : instant(m.get("last_synced_at")));
            })
        .toList();
  }

  /** Internal control-plane use case; no public cancel API is frozen in Client API v2. */
  @Transactional
  public String requestCancellation(String runId, String reasonSummary) {
    if (reasonSummary == null || reasonSummary.isBlank() || reasonSummary.length() > 800)
      throw DomainException.rejected("reasonSummary must contain 1 to 800 characters");
    Command existing = repository.findCancelCommand(runId).orElse(null);
    if (existing != null) return existing.id();
    Binding b =
        repository.findBinding(runId).orElseThrow(() -> DomainException.notFound("Run", runId));
    if (!List.of("assigned", "running", "waiting_interaction").contains(b.runStatus()))
      throw DomainException.conflict("Run state does not allow cancellation");
    Instant now = clock.instant();
    String command = protocol.id("cmd_");
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    header(payload, command, b.nodeId(), now, b.workItemId(), b.missionId(), b.runId(), b.digest());
    payload.put("commandAvailable", true);
    payload.put("commandType", "CANCEL_RUN");
    payload.put("commandId", command);
    payload.put("targetNodeId", b.nodeId());
    payload.put("reasonSummary", reasonSummary);
    String json = protocol.json(payload);
    repository.insertCommand(
        command,
        b.nodeId(),
        b.workItemId(),
        b.missionId(),
        b.runId(),
        b.digest(),
        "CANCEL_RUN",
        json,
        protocol.hashJson(json),
        null,
        now);
    repository.updateRun(runId, "cancelling", b.resumable(), null, null, null, now);
    repository.insertTimeline(command, runId, "CANCEL_REQUESTED", reasonSummary, now);
    return command;
  }

  private WorkItemSummary summary(Map<String, Object> m) {
    return new WorkItemSummary(
        (String) m.get("work_item_id"),
        (String) m.get("title"),
        (String) m.get("status"),
        ((Number) m.get("priority")).intValue(),
        (String) m.get("phase_code"),
        (String) m.get("progress_summary"),
        ((Number) m.get("waiting_count")).intValue(),
        instant(m.get("last_synced_at")),
        Instant.parse((String) m.get("updated_at")));
  }

  private InteractionSummary interaction(Interaction i) {
    return new InteractionSummary(
        i.id(),
        i.runId(),
        i.checkpointId(),
        i.state(),
        i.prompt(),
        readList(i.allowedDecisionsJson()),
        i.response(),
        i.resolvedAt(),
        i.consumedAt(),
        i.createdAt());
  }

  private List<String> readList(String json) {
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Stored decisions are invalid", e);
    }
  }

  private Instant instant(Object value) {
    return value == null ? null : Instant.parse((String) value);
  }

  private <T> T replay(Idempotency old, String operation, String hash, Class<T> type) {
    if (!old.operation().equals(operation) || !old.requestHash().equals(hash))
      throw DomainException.conflict("Idempotency-Key was used for different content");
    T response = protocol.read(old.responseJson(), type);
    if (response instanceof InteractionResolution r)
      return type.cast(
          new InteractionResolution(
              r.interactionId(), r.state(), r.commandId(), true, r.resolvedAt()));
    if (response instanceof NotificationDeliveryAck r)
      return type.cast(new NotificationDeliveryAck(r.notificationId(), r.status(), true));
    return response;
  }

  private void header(
      Map<String, Object> p,
      String messageId,
      String nodeId,
      Instant now,
      String work,
      String mission,
      String run,
      String digest) {
    p.put("protocolVersion", "2.0");
    p.put("messageId", messageId);
    p.put("nodeId", nodeId);
    p.put("sentAt", now);
    p.put("workItemId", work);
    p.put("missionId", mission);
    p.put("runId", run);
    p.put("missionDigest", digest);
  }
}
