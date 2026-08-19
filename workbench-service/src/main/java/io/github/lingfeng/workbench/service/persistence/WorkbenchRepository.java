package io.github.lingfeng.workbench.service.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.IdempotencyRecord;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Interaction;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Mission;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Node;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.NodeMessage;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Run;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.Timeline;
import io.github.lingfeng.workbench.service.domain.ServiceRecords.WorkItem;
import io.github.lingfeng.workbench.service.domain.Statuses.MissionStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.RunStatus;
import io.github.lingfeng.workbench.service.domain.Statuses.WorkItemStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkbenchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkbenchRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertWorkItem(WorkItem item) {
        jdbc.update("INSERT INTO work_items VALUES (?, ?, ?, ?, ?, ?)", item.id(), item.title(),
                item.status().name(), item.priority(), item.createdAt().toString(), item.updatedAt().toString());
    }

    public void insertMission(Mission mission) {
        jdbc.update("""
                INSERT INTO missions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, mission.id(), mission.workItemId(), mission.revision(), mission.digest(), mission.objective(),
                mission.acceptanceSummary(), mission.authorizedSideEffectsSummary(), mission.targetNodeId(),
                mission.workspaceRef(), mission.runtimeKind(), mission.executionProfile(), mission.status().name(),
                mission.createdAt().toString(), mission.updatedAt().toString());
    }

    public void insertIdempotencyRecord(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_records VALUES (?, ?, ?, ?, ?, ?)", record.key(), record.requestHash(),
                record.workItemId(), record.missionId(), record.missionDigest(), record.createdAt().toString());
    }

    public Optional<IdempotencyRecord> findIdempotencyRecord(String key) {
        return queryOne("SELECT * FROM idempotency_records WHERE idempotency_key=?", this::mapIdempotency, key);
    }

    public List<WorkItem> listWorkItems(int limit) {
        return jdbc.query("SELECT * FROM work_items ORDER BY updated_at DESC LIMIT ?", this::mapWorkItem, limit);
    }

    public Optional<WorkItem> findWorkItem(String id) {
        return queryOne("SELECT * FROM work_items WHERE work_item_id=?", this::mapWorkItem, id);
    }

    public Optional<Mission> findMission(String id) {
        return queryOne("SELECT * FROM missions WHERE mission_id=?", this::mapMission, id);
    }

    public List<Mission> findMissionsForWorkItem(String workItemId) {
        return jdbc.query("SELECT * FROM missions WHERE work_item_id=? ORDER BY created_at", this::mapMission, workItemId);
    }

    public Optional<Run> findRun(String id) {
        return queryOne("SELECT * FROM runs WHERE run_id=?", this::mapRun, id);
    }

    public List<Run> findRunsForMission(String missionId) {
        return jdbc.query("SELECT * FROM runs WHERE mission_id=? ORDER BY created_at", this::mapRun, missionId);
    }

    public List<Timeline> findTimeline(String runId) {
        return jdbc.query("SELECT * FROM timeline_events WHERE run_id=? ORDER BY created_at DESC LIMIT 100",
                this::mapTimeline, runId);
    }

    public List<Interaction> listInteractions(String state) {
        if (state == null) {
            return jdbc.query("SELECT * FROM interactions ORDER BY created_at DESC", this::mapInteraction);
        }
        return jdbc.query("SELECT * FROM interactions WHERE state=? ORDER BY created_at DESC", this::mapInteraction, state);
    }

    public void upsertNode(String nodeId, String displayName, List<String> capabilities, Instant now) {
        String capabilitiesJson = writeJson(capabilities);
        jdbc.update("""
                INSERT INTO nodes(node_id, display_name, capabilities_json, last_heartbeat_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(node_id) DO UPDATE SET display_name=excluded.display_name,
                capabilities_json=excluded.capabilities_json, last_heartbeat_at=excluded.last_heartbeat_at,
                updated_at=excluded.updated_at
                """, nodeId, displayName, capabilitiesJson, now.toString(), now.toString(), now.toString());
    }

    public boolean updateHeartbeat(String nodeId, Instant now) {
        return jdbc.update("UPDATE nodes SET last_heartbeat_at=?, updated_at=? WHERE node_id=?",
                now.toString(), now.toString(), nodeId) == 1;
    }

    public Optional<Node> findNode(String nodeId) {
        return queryOne("SELECT * FROM nodes WHERE node_id=?", this::mapNode, nodeId);
    }

    public List<Node> listNodes() {
        return jdbc.query("SELECT * FROM nodes ORDER BY node_id", this::mapNode);
    }

    public Optional<Run> findUnacknowledgedAssignment(String nodeId) {
        return queryOne("""
                SELECT * FROM runs WHERE node_id=? AND status='assigned' AND command_acknowledged_at IS NULL
                ORDER BY created_at LIMIT 1
                """, this::mapRun, nodeId);
    }

    public Optional<Mission> findFirstPendingMission(String nodeId) {
        return queryOne("""
                SELECT * FROM missions WHERE target_node_id=? AND status='pending' ORDER BY created_at LIMIT 1
                """, this::mapMission, nodeId);
    }

    public boolean assignMission(String missionId, Instant now) {
        return jdbc.update("UPDATE missions SET status='assigned', updated_at=? WHERE mission_id=? AND status='pending'",
                now.toString(), missionId) == 1;
    }

    public void insertRun(Run run) {
        jdbc.update("""
                INSERT INTO runs(run_id, mission_id, node_id, command_id, status, progress_summary,
                result_summary, resumable, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.missionId(), run.nodeId(), run.commandId(), run.status().name(),
                run.progressSummary(), run.resultSummary(), run.resumable() ? 1 : 0,
                run.createdAt().toString(), run.updatedAt().toString());
    }

    public void acknowledgeCommands(String nodeId, List<String> commandIds, Instant now) {
        commandIds.forEach(commandId -> jdbc.update("""
                UPDATE runs SET command_acknowledged_at=?, updated_at=? WHERE node_id=? AND command_id=?
                """, now.toString(), now.toString(), nodeId, commandId));
    }

    public void acknowledgeCommand(String nodeId, String commandId, Instant now) {
        jdbc.update("UPDATE runs SET command_acknowledged_at=?, updated_at=? WHERE node_id=? AND command_id=?",
                now.toString(), now.toString(), nodeId, commandId);
    }

    public void updateRunStatus(String runId, RunStatus status, boolean resumable, String progress,
            String result, Instant now) {
        jdbc.update("""
                UPDATE runs SET status=?, resumable=?, progress_summary=COALESCE(?, progress_summary),
                result_summary=COALESCE(?, result_summary), updated_at=? WHERE run_id=?
                """, status.name(), resumable ? 1 : 0, progress, result, now.toString(), runId);
    }

    public void updateMissionStatus(String missionId, MissionStatus status, Instant now) {
        jdbc.update("UPDATE missions SET status=?, updated_at=? WHERE mission_id=?",
                status.name(), now.toString(), missionId);
    }

    public void updateWorkItemStatus(String workItemId, WorkItemStatus status, Instant now) {
        jdbc.update("UPDATE work_items SET status=?, updated_at=? WHERE work_item_id=?",
                status.name(), now.toString(), workItemId);
    }

    public void insertTimeline(Timeline event) {
        jdbc.update("INSERT INTO timeline_events VALUES (?, ?, ?, ?, ?)", event.id(), event.runId(),
                event.eventType(), event.summary(), event.createdAt().toString());
    }

    public Optional<NodeMessage> findNodeMessage(String messageId) {
        return queryOne("SELECT * FROM node_messages WHERE message_id=?", this::mapNodeMessage, messageId);
    }

    public void insertNodeMessage(NodeMessage message) {
        try {
            jdbc.update("INSERT INTO node_messages VALUES (?, ?, ?, ?, ?)", message.messageId(), message.nodeId(),
                    message.messageType(), message.payloadHash(), message.createdAt().toString());
        } catch (DuplicateKeyException exception) {
            throw DomainException.conflict("messageId was concurrently used");
        }
    }

    private <T> Optional<T> queryOne(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.stream().findFirst();
    }

    private WorkItem mapWorkItem(ResultSet row, int number) throws SQLException {
        return new WorkItem(row.getString("work_item_id"), row.getString("title"),
                WorkItemStatus.valueOf(row.getString("status")), row.getInt("priority"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private Mission mapMission(ResultSet row, int number) throws SQLException {
        return new Mission(row.getString("mission_id"), row.getString("work_item_id"), row.getInt("revision"),
                row.getString("mission_digest"), row.getString("objective"), row.getString("acceptance_summary"),
                row.getString("authorized_side_effects_summary"), row.getString("target_node_id"),
                row.getString("workspace_ref"), row.getString("runtime_kind"), row.getString("execution_profile"),
                MissionStatus.valueOf(row.getString("status")), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private Run mapRun(ResultSet row, int number) throws SQLException {
        return new Run(row.getString("run_id"), row.getString("mission_id"), row.getString("node_id"),
                row.getString("command_id"), RunStatus.valueOf(row.getString("status")),
                row.getString("progress_summary"), row.getString("result_summary"), row.getInt("resumable") == 1,
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private Node mapNode(ResultSet row, int number) throws SQLException {
        try {
            List<String> capabilities = objectMapper.readValue(row.getString("capabilities_json"), new TypeReference<>() {});
            return new Node(row.getString("node_id"), row.getString("display_name"), capabilities,
                    instant(row, "last_heartbeat_at"));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored node capabilities are invalid", exception);
        }
    }

    private Timeline mapTimeline(ResultSet row, int number) throws SQLException {
        return new Timeline(row.getString("event_id"), row.getString("run_id"), row.getString("event_type"),
                row.getString("summary"), instant(row, "created_at"));
    }

    private Interaction mapInteraction(ResultSet row, int number) throws SQLException {
        return new Interaction(row.getString("interaction_id"), row.getString("run_id"),
                row.getString("checkpoint_id"), row.getString("mission_digest"), row.getString("state"),
                row.getString("prompt_summary"), instant(row, "created_at"));
    }

    private IdempotencyRecord mapIdempotency(ResultSet row, int number) throws SQLException {
        return new IdempotencyRecord(row.getString("idempotency_key"), row.getString("request_hash"),
                row.getString("work_item_id"), row.getString("mission_id"), row.getString("mission_digest"),
                instant(row, "created_at"));
    }

    private NodeMessage mapNodeMessage(ResultSet row, int number) throws SQLException {
        return new NodeMessage(row.getString("message_id"), row.getString("node_id"),
                row.getString("message_type"), row.getString("payload_hash"), instant(row, "created_at"));
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        return Instant.parse(row.getString(column));
    }

    private String writeJson(List<String> capabilities) {
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Capabilities cannot be serialized", exception);
        }
    }
}
