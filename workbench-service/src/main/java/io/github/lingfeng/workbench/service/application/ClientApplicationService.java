package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.CreateWorkItemRequest;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.CreatedWorkItem;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.InteractionSummary;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.MissionDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.NodeSummary;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.RunDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.RunSummary;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.TimelineEvent;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.WorkItemDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.WorkItemSummary;
import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator;
import io.github.lingfeng.workbench.service.domain.MissionDigestCalculator.MissionContract;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.IdempotencyRecord;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Mission;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.WorkItem;
import io.github.lingfeng.workbench.service.domain.Statuses.MissionStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.WorkItemStatus;
import io.github.lingfeng.workbench.service.persistence.WorkbenchRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientApplicationService {
    private final WorkbenchRepository repository;
    private final MissionDigestCalculator digestCalculator;
    private final ObjectMapper objectMapper;
    private final WorkbenchProperties properties;
    private final Clock clock;

    @Autowired
    public ClientApplicationService(
            WorkbenchRepository repository,
            MissionDigestCalculator digestCalculator,
            ObjectMapper objectMapper,
            WorkbenchProperties properties) {
        this(repository, digestCalculator, objectMapper, properties, Clock.systemUTC());
    }

    ClientApplicationService(WorkbenchRepository repository, MissionDigestCalculator digestCalculator,
            ObjectMapper objectMapper, WorkbenchProperties properties, Clock clock) {
        this.repository = repository;
        this.digestCalculator = digestCalculator;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public CreatedWorkItem createWorkItem(String idempotencyKey, CreateWorkItemRequest request) {
        if (!Boolean.TRUE.equals(request.dataBoundaryAcknowledged())) {
            throw DomainException.rejected("dataBoundaryAcknowledged must be true");
        }
        String requestHash = hashRequest(request);
        var existing = repository.findIdempotencyRecord(idempotencyKey);
        if (existing.isPresent()) {
            return replayIdempotentResult(existing.get(), requestHash);
        }

        Instant now = clock.instant();
        String workItemId = newId("wi_");
        String missionId = newId("mi_");
        MissionContract contract = new MissionContract(request.objective(), request.acceptanceSummary(),
                request.authorizedSideEffectsSummary(), request.targetNodeId(), request.workspaceRef(),
                request.runtimeKind(), request.executionProfile(), 1);
        String missionDigest = digestCalculator.calculate(contract);
        repository.insertWorkItem(new WorkItem(workItemId, request.title(), WorkItemStatus.open,
                request.effectivePriority(), now, now));
        repository.insertMission(new Mission(missionId, workItemId, 1, missionDigest, request.objective(),
                request.acceptanceSummary(), request.authorizedSideEffectsSummary(), request.targetNodeId(),
                request.workspaceRef(), request.runtimeKind(), request.executionProfile(), MissionStatus.pending, now, now));
        repository.insertIdempotencyRecord(new IdempotencyRecord(idempotencyKey, requestHash, workItemId,
                missionId, missionDigest, now));
        return new CreatedWorkItem(workItemId, missionId, missionDigest, now);
    }

    @Transactional(readOnly = true)
    public List<WorkItemSummary> listWorkItems(int limit) {
        return repository.listWorkItems(limit).stream().map(this::toWorkItemSummary).toList();
    }

    @Transactional(readOnly = true)
    public WorkItemDetail getWorkItem(String workItemId) {
        WorkItem item = repository.findWorkItem(workItemId)
                .orElseThrow(() -> DomainException.notFound("WorkItem", workItemId));
        List<MissionDetail> missions = repository.findMissionsForWorkItem(workItemId).stream()
                .map(this::toMissionDetail).toList();
        return new WorkItemDetail(item.id(), item.title(), item.status(), item.priority(), item.updatedAt(), missions);
    }

    @Transactional(readOnly = true)
    public MissionDetail getMission(String missionId) {
        return toMissionDetail(repository.findMission(missionId)
                .orElseThrow(() -> DomainException.notFound("Mission", missionId)));
    }

    @Transactional(readOnly = true)
    public RunDetail getRun(String runId) {
        var run = repository.findRun(runId).orElseThrow(() -> DomainException.notFound("Run", runId));
        List<TimelineEvent> timeline = repository.findTimeline(runId).stream()
                .map(event -> new TimelineEvent(event.id(), event.eventType(), event.summary(), event.createdAt()))
                .toList();
        return new RunDetail(run.id(), run.missionId(), run.nodeId(), run.status(), run.progressSummary(),
                run.resultSummary(), run.resumable(), run.updatedAt(), timeline);
    }

    @Transactional(readOnly = true)
    public List<NodeSummary> listNodes() {
        Instant offlineBefore = clock.instant().minus(properties.node().offlineAfter());
        return repository.listNodes().stream().map(node -> new NodeSummary(node.id(), node.displayName(),
                node.lastHeartbeatAt().isBefore(offlineBefore) ? "offline" : "online",
                node.capabilities(), node.lastHeartbeatAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<InteractionSummary> listInteractions(String state) {
        return repository.listInteractions(state).stream().map(interaction -> new InteractionSummary(
                interaction.id(), interaction.runId(), interaction.checkpointId(), interaction.missionDigest(),
                interaction.state(), interaction.promptSummary(), interaction.createdAt())).toList();
    }

    private MissionDetail toMissionDetail(Mission mission) {
        List<RunSummary> runs = repository.findRunsForMission(mission.id()).stream().map(run -> new RunSummary(
                run.id(), run.missionId(), run.nodeId(), run.status(), run.progressSummary(), run.resultSummary(),
                run.resumable(), run.updatedAt())).toList();
        return new MissionDetail(mission.id(), mission.workItemId(), mission.revision(), mission.digest(),
                mission.objective(), mission.acceptanceSummary(), mission.authorizedSideEffectsSummary(),
                mission.targetNodeId(), mission.workspaceRef(), mission.runtimeKind(), mission.executionProfile(),
                mission.status(), runs, mission.createdAt(), mission.updatedAt());
    }

    private WorkItemSummary toWorkItemSummary(WorkItem item) {
        return new WorkItemSummary(item.id(), item.title(), item.status(), item.priority(), item.updatedAt());
    }

    private CreatedWorkItem replayIdempotentResult(IdempotencyRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw DomainException.conflict("Idempotency-Key was already used for a different request");
        }
        return new CreatedWorkItem(record.workItemId(), record.missionId(), record.missionDigest(), record.createdAt());
    }

    private String hashRequest(CreateWorkItemRequest request) {
        try {
            return sha256(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Create request cannot be serialized", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
