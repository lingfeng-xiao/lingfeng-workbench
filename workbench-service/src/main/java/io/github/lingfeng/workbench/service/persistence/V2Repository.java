package io.github.lingfeng.workbench.service.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class V2Repository {
  private final JdbcTemplate jdbc;

  public V2Repository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Idempotency> findIdempotency(String principal, String key) {
    return one(
        "SELECT * FROM client_idempotency_v2 WHERE principal_kind=? AND idempotency_key=?",
        (row, rowNumber) ->
            new Idempotency(
                row.getString("operation"),
                row.getString("request_hash"),
                row.getString("response_json")),
        principal,
        key);
  }

  public void insertIdempotency(
      String principal,
      String key,
      String operation,
      String hash,
      String responseJson,
      Instant now) {
    jdbc.update(
        "INSERT INTO client_idempotency_v2 VALUES (?,?,?,?,?,?)",
        principal,
        key,
        operation,
        hash,
        responseJson,
        now.toString());
  }

  public void insertAggregate(
      String workItemId,
      String title,
      int priority,
      String missionId,
      String digest,
      String objective,
      String acceptance,
      String sideEffects,
      String nodeId,
      String workspaceRef,
      String runtimeKind,
      String profile,
      String runId,
      String commandId,
      Instant now) {
    String timestamp = now.toString();
    jdbc.update(
        "INSERT INTO work_items VALUES (?,?,?,?,?,?)",
        workItemId,
        title,
        "in_progress",
        priority,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO missions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        missionId,
        workItemId,
        1,
        digest,
        objective,
        acceptance,
        sideEffects,
        nodeId,
        workspaceRef,
        runtimeKind,
        profile,
        "assigned",
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO"
            + " runs(run_id,mission_id,node_id,command_id,status,resumable,created_at,updated_at,last_synced_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        runId,
        missionId,
        nodeId,
        commandId,
        "assigned",
        0,
        timestamp,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO timeline_events VALUES (?,?,?,?,?)",
        "ev_" + commandId,
        runId,
        "ASSIGNED",
        "Run assigned",
        timestamp);
  }

  public void insertCommand(
      String commandId,
      String nodeId,
      String workItemId,
      String missionId,
      String runId,
      String digest,
      String type,
      String payloadJson,
      String payloadDigest,
      String interactionId,
      Instant now) {
    jdbc.update(
        "INSERT INTO control_commands VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL)",
        commandId,
        nodeId,
        workItemId,
        missionId,
        runId,
        digest,
        type,
        payloadJson,
        payloadDigest,
        interactionId,
        now.toString());
  }

  public Optional<Command> pollCommand(String nodeId) {
    return one(
        "SELECT * FROM control_commands WHERE target_node_id=? AND acknowledged_at IS NULL ORDER BY"
            + " created_at,command_id LIMIT 1",
        this::command,
        nodeId);
  }

  public Optional<Command> findCommand(String commandId) {
    return one("SELECT * FROM control_commands WHERE command_id=?", this::command, commandId);
  }

  public Optional<Command> findCancelCommand(String runId) {
    return one(
        "SELECT * FROM control_commands WHERE run_id=? AND command_type='CANCEL_RUN' ORDER BY"
            + " created_at LIMIT 1",
        this::command,
        runId);
  }

  public void acknowledgeCommand(String commandId, Instant now) {
    jdbc.update(
        "UPDATE control_commands SET acknowledged_at=COALESCE(acknowledged_at,?) WHERE"
            + " command_id=?",
        now.toString(),
        commandId);
  }

  public Optional<Binding> findBinding(String runId) {
    return one(
        """
SELECT r.run_id,r.node_id,r.status run_status,r.phase_code,r.progress_summary,r.result_summary,
r.resumable,r.last_synced_at,r.updated_at,m.mission_id,m.work_item_id,m.mission_digest,
m.revision,m.objective,m.acceptance_summary,m.status mission_status,w.title,w.status work_status,
w.priority,w.updated_at work_updated_at
FROM runs r JOIN missions m ON m.mission_id=r.mission_id
JOIN work_items w ON w.work_item_id=m.work_item_id WHERE r.run_id=?
""",
        this::binding,
        runId);
  }

  public Optional<EventDedup> findEvent(String nodeId, String messageId) {
    return one(
        "SELECT * FROM node_event_dedup_v2 WHERE node_id=? AND message_id=?",
        (row, rowNumber) ->
            new EventDedup(row.getString("event_type"), row.getString("payload_hash")),
        nodeId,
        messageId);
  }

  public void insertEvent(
      String nodeId, String messageId, String eventType, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO node_event_dedup_v2 VALUES (?,?,?,?,?)",
        nodeId,
        messageId,
        eventType,
        hash,
        now.toString());
  }

  public void updateRun(
      String runId,
      String status,
      Boolean resumable,
      String phaseCode,
      String progress,
      String result,
      Instant now) {
    jdbc.update(
        """
        UPDATE runs SET status=?,resumable=COALESCE(?,resumable),phase_code=COALESCE(?,phase_code),
        progress_summary=COALESCE(?,progress_summary),result_summary=COALESCE(?,result_summary),
        last_synced_at=?,updated_at=? WHERE run_id=?
        """,
        status,
        resumable == null ? null : resumable ? 1 : 0,
        phaseCode,
        progress,
        result,
        now.toString(),
        now.toString(),
        runId);
  }

  public void updateAggregateStatus(
      String missionId, String workItemId, String missionStatus, String workStatus, Instant now) {
    jdbc.update(
        "UPDATE missions SET status=?,updated_at=? WHERE mission_id=?",
        missionStatus,
        now.toString(),
        missionId);
    jdbc.update(
        "UPDATE work_items SET status=?,updated_at=? WHERE work_item_id=?",
        workStatus,
        now.toString(),
        workItemId);
  }

  public void insertTimeline(
      String messageId, String runId, String type, String summary, Instant now) {
    jdbc.update(
        "INSERT INTO timeline_events VALUES (?,?,?,?,?)",
        "ev_" + messageId,
        runId,
        type,
        summary,
        now.toString());
  }

  public Optional<Interaction> findInteraction(String id) {
    return one("SELECT * FROM interactions WHERE interaction_id=?", this::interaction, id);
  }

  public List<Interaction> listInteractions(String state, int limit) {
    if (state == null)
      return jdbc.query(
          "SELECT * FROM interactions ORDER BY created_at DESC LIMIT ?", this::interaction, limit);
    return jdbc.query(
        "SELECT * FROM interactions WHERE state=? ORDER BY created_at DESC LIMIT ?",
        this::interaction,
        state,
        limit);
  }

  public void insertInteraction(Interaction i) {
    jdbc.update(
        """
INSERT INTO interactions(interaction_id,run_id,checkpoint_id,mission_digest,state,prompt_summary,
created_at,target_node_id,allowed_decisions_json) VALUES (?,?,?,?,?,?,?,?,?)
""",
        i.id(),
        i.runId(),
        i.checkpointId(),
        i.digest(),
        i.state(),
        i.prompt(),
        i.createdAt().toString(),
        i.nodeId(),
        i.allowedDecisionsJson());
  }

  public void resolveInteraction(String id, String summary, Instant resolvedAt, String commandId) {
    jdbc.update(
        "UPDATE interactions SET"
            + " state='resolved',response_summary=?,resolved_at=?,response_command_id=? WHERE"
            + " interaction_id=? AND state='pending'",
        summary,
        resolvedAt.toString(),
        commandId,
        id);
  }

  public void deliverInteraction(String id) {
    jdbc.update(
        "UPDATE interactions SET state='delivered' WHERE interaction_id=? AND state='resolved'",
        id);
  }

  public void consumeInteraction(String id, Instant now) {
    jdbc.update(
        "UPDATE interactions SET state='consumed',consumed_at=? WHERE interaction_id=? AND"
            + " state='delivered'",
        now.toString(),
        id);
  }

  public void insertNotification(Notification notification) {
    jdbc.update(
        """
INSERT OR IGNORE INTO notification_delivery(notification_id,dedup_key,notification_type,target_alias,
work_item_id,mission_id,run_id,interaction_id,title,message_summary,status,attempt,created_at,updated_at)
VALUES (?,?,?,?,?,?,?,?,?,?,'pending',0,?,?)
""",
        notification.id(),
        notification.dedupKey(),
        notification.type(),
        "owner",
        notification.workItemId(),
        notification.missionId(),
        notification.runId(),
        notification.interactionId(),
        notification.title(),
        notification.summary(),
        notification.createdAt().toString(),
        notification.createdAt().toString());
  }

  public Optional<Notification> leaseNotification(String target, Instant now, Instant expires) {
    Optional<Notification> selected =
        one(
            """
SELECT * FROM notification_delivery WHERE target_alias=? AND status IN ('pending','leased')
AND (status='pending' OR lease_expires_at<=?) ORDER BY created_at LIMIT 1
""",
            this::notification,
            target,
            now.toString());
    selected.ifPresent(
        notification ->
            jdbc.update(
                "UPDATE notification_delivery SET"
                    + " status='leased',attempt=attempt+1,lease_expires_at=?,updated_at=? WHERE"
                    + " notification_id=?",
                expires.toString(),
                now.toString(),
                notification.id()));
    return selected.flatMap(
        notification ->
            one(
                "SELECT * FROM notification_delivery WHERE notification_id=?",
                this::notification,
                notification.id()));
  }

  public Optional<Notification> findNotification(String id) {
    return one(
        "SELECT * FROM notification_delivery WHERE notification_id=?", this::notification, id);
  }

  public Optional<String> findDeliveryHash(String notificationId, String eventId) {
    return one(
        "SELECT payload_hash FROM notification_delivery_event WHERE notification_id=? AND"
            + " delivery_event_id=?",
        (row, rowNumber) -> row.getString(1),
        notificationId,
        eventId);
  }

  public void reportDelivery(
      String notificationId,
      String eventId,
      String hash,
      String outcome,
      Instant reportedAt,
      int maxAttempts) {
    jdbc.update(
        "INSERT INTO notification_delivery_event VALUES (?,?,?,?,?)",
        notificationId,
        eventId,
        hash,
        outcome,
        reportedAt.toString());
    if ("DELIVERED".equals(outcome)) {
      jdbc.update(
          "UPDATE notification_delivery SET status='delivered',lease_expires_at=NULL,updated_at=?"
              + " WHERE notification_id=?",
          reportedAt.toString(),
          notificationId);
    } else {
      jdbc.update(
          "UPDATE notification_delivery SET status=CASE WHEN attempt>=? THEN 'dead_letter' ELSE"
              + " 'pending' END,lease_expires_at=NULL,updated_at=? WHERE notification_id=?",
          maxAttempts,
          reportedAt.toString(),
          notificationId);
    }
  }

  public List<Notification> notificationsForWorkItem(String id) {
    return jdbc.query(
        "SELECT * FROM notification_delivery WHERE work_item_id=? ORDER BY created_at DESC LIMIT"
            + " 100",
        this::notification,
        id);
  }

  public List<Map<String, Object>> timeline(String runId) {
    return jdbc.queryForList(
        "SELECT event_id,event_type,summary,created_at FROM timeline_events WHERE run_id=? ORDER BY"
            + " created_at DESC LIMIT 100",
        runId);
  }

  public List<Map<String, Object>> listSummaries(int limit) {
    return jdbc.queryForList(
        """
SELECT w.work_item_id,w.title,w.status,w.priority,w.updated_at,r.phase_code,r.progress_summary,
r.last_synced_at,(SELECT count(*) FROM interactions i WHERE i.run_id=r.run_id AND i.state='pending') waiting_count
FROM work_items w JOIN missions m ON m.work_item_id=w.work_item_id
JOIN runs r ON r.mission_id=m.mission_id ORDER BY w.updated_at DESC LIMIT ?
""",
        limit);
  }

  public List<Map<String, Object>> listNodeSummaries() {
    return jdbc.queryForList(
        """
SELECT n.node_id,n.display_name,n.capabilities_json,n.last_heartbeat_at,n.updated_at,
(SELECT r.run_id FROM runs r WHERE r.node_id=n.node_id AND r.status IN
('assigned','running','waiting_interaction','cancelling') ORDER BY r.created_at DESC LIMIT 1) current_run_id,
(SELECT max(r.last_synced_at) FROM runs r WHERE r.node_id=n.node_id) last_synced_at
FROM nodes n ORDER BY n.node_id LIMIT 100
""");
  }

  public Optional<Binding> findWorkItemBinding(String workItemId) {
    return one(
        """
SELECT r.run_id,r.node_id,r.status run_status,r.phase_code,r.progress_summary,r.result_summary,
r.resumable,r.last_synced_at,r.updated_at,m.mission_id,m.work_item_id,m.mission_digest,
m.revision,m.objective,m.acceptance_summary,m.status mission_status,w.title,w.status work_status,
w.priority,w.updated_at work_updated_at FROM work_items w JOIN missions m ON m.work_item_id=w.work_item_id
JOIN runs r ON r.mission_id=m.mission_id WHERE w.work_item_id=? ORDER BY r.created_at DESC LIMIT 1
""",
        this::binding,
        workItemId);
  }

  public List<Binding> findOfflineActiveRuns(Instant offlineBefore) {
    return jdbc.query(
        """
SELECT r.run_id,r.node_id,r.status run_status,r.phase_code,r.progress_summary,r.result_summary,
r.resumable,r.last_synced_at,r.updated_at,m.mission_id,m.work_item_id,m.mission_digest,
m.revision,m.objective,m.acceptance_summary,m.status mission_status,w.title,w.status work_status,
w.priority,w.updated_at work_updated_at
FROM nodes n JOIN runs r ON r.node_id=n.node_id
JOIN missions m ON m.mission_id=r.mission_id JOIN work_items w ON w.work_item_id=m.work_item_id
WHERE n.last_heartbeat_at<? AND r.status IN ('assigned','running','waiting_interaction','cancelling')
""",
        this::binding,
        offlineBefore.toString());
  }

  private Command command(ResultSet row, int rowNumber) throws SQLException {
    return new Command(
        row.getString("command_id"),
        row.getString("target_node_id"),
        row.getString("work_item_id"),
        row.getString("mission_id"),
        row.getString("run_id"),
        row.getString("mission_digest"),
        row.getString("command_type"),
        row.getString("payload_json"),
        row.getString("payload_digest"),
        row.getString("interaction_id"),
        instant(row, "created_at"));
  }

  private Binding binding(ResultSet row, int rowNumber) throws SQLException {
    return new Binding(
        row.getString("work_item_id"),
        row.getString("mission_id"),
        row.getString("run_id"),
        row.getString("node_id"),
        row.getString("mission_digest"),
        row.getString("run_status"),
        row.getString("mission_status"),
        row.getString("work_status"),
        row.getString("title"),
        row.getInt("priority"),
        row.getInt("revision"),
        row.getString("objective"),
        row.getString("acceptance_summary"),
        row.getString("phase_code"),
        row.getString("progress_summary"),
        row.getString("result_summary"),
        row.getInt("resumable") == 1,
        instantNullable(row, "last_synced_at"),
        instant(row, "work_updated_at"));
  }

  private Interaction interaction(ResultSet row, int rowNumber) throws SQLException {
    return new Interaction(
        row.getString("interaction_id"),
        row.getString("run_id"),
        row.getString("checkpoint_id"),
        row.getString("mission_digest"),
        row.getString("target_node_id"),
        row.getString("state"),
        row.getString("prompt_summary"),
        row.getString("allowed_decisions_json"),
        row.getString("response_summary"),
        instantNullable(row, "resolved_at"),
        instantNullable(row, "consumed_at"),
        row.getString("response_command_id"),
        instant(row, "created_at"));
  }

  private Notification notification(ResultSet row, int rowNumber) throws SQLException {
    return new Notification(
        row.getString("notification_id"),
        row.getString("dedup_key"),
        row.getString("notification_type"),
        row.getString("work_item_id"),
        row.getString("mission_id"),
        row.getString("run_id"),
        row.getString("interaction_id"),
        row.getString("title"),
        row.getString("message_summary"),
        row.getString("status"),
        row.getInt("attempt"),
        instantNullable(row, "lease_expires_at"),
        instant(row, "created_at"));
  }

  private Instant instant(ResultSet row, String column) throws SQLException {
    return Instant.parse(row.getString(column));
  }

  private Instant instantNullable(ResultSet row, String column) throws SQLException {
    String storedValue = row.getString(column);
    return storedValue == null ? null : Instant.parse(storedValue);
  }

  private <T> Optional<T> one(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return jdbc.query(sql, mapper, args).stream().findFirst();
  }

  public record Idempotency(String operation, String requestHash, String responseJson) {}

  public record EventDedup(String eventType, String payloadHash) {}

  public record Command(
      String id,
      String nodeId,
      String workItemId,
      String missionId,
      String runId,
      String digest,
      String type,
      String payloadJson,
      String payloadDigest,
      String interactionId,
      Instant createdAt) {}

  public record Binding(
      String workItemId,
      String missionId,
      String runId,
      String nodeId,
      String digest,
      String runStatus,
      String missionStatus,
      String workStatus,
      String title,
      int priority,
      int revision,
      String objective,
      String acceptance,
      String phase,
      String progress,
      String result,
      boolean resumable,
      Instant lastSyncedAt,
      Instant updatedAt) {}

  public record Interaction(
      String id,
      String runId,
      String checkpointId,
      String digest,
      String nodeId,
      String state,
      String prompt,
      String allowedDecisionsJson,
      String response,
      Instant resolvedAt,
      Instant consumedAt,
      String responseCommandId,
      Instant createdAt) {}

  public record Notification(
      String id,
      String dedupKey,
      String type,
      String workItemId,
      String missionId,
      String runId,
      String interactionId,
      String title,
      String summary,
      String status,
      int attempt,
      Instant leaseExpiresAt,
      Instant createdAt) {
    public Notification(
        String id,
        String dedupKey,
        String type,
        String workItemId,
        String missionId,
        String runId,
        String interactionId,
        String title,
        String summary,
        Instant createdAt) {
      this(
          id,
          dedupKey,
          type,
          workItemId,
          missionId,
          runId,
          interactionId,
          title,
          summary,
          "pending",
          0,
          null,
          createdAt);
    }
  }
}
