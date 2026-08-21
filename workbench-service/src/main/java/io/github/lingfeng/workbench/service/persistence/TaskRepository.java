package io.github.lingfeng.workbench.service.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
  private final JdbcTemplate jdbc;

  public TaskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertTask(TaskRecord task) {
    jdbc.update(
        """
INSERT INTO tasks(task_id,title,objective,acceptance_summary,side_effect_summary,priority,
target_node_id,workspace_ref,context_refs_json,runtime_kind,execution_profile,business_status,
acceptance_status,attention_state,version,created_by,updated_by,created_at,updated_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?,'DRAFT',
'NOT_REQUESTED','NONE',1,?,?,?,?)
""",
        task.taskId(),
        task.title(),
        task.objective(),
        task.acceptanceSummary(),
        task.sideEffectSummary(),
        task.priority(),
        task.targetNodeId(),
        task.workspaceRef(),
        task.contextRefsJson(),
        task.runtimeKind(),
        task.executionProfile(),
        task.createdBy(),
        task.updatedBy(),
        task.createdAt().toString(),
        task.updatedAt().toString());
  }

  public Optional<TaskRecord> findTask(String taskId) {
    return one("SELECT * FROM tasks WHERE task_id=?", this::task, taskId);
  }

  public List<TaskRecord> listTasks(
      String businessStatus,
      String attentionState,
      String targetNodeId,
      boolean includeArchived,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM tasks WHERE 1=1");
    List<Object> parameters = new ArrayList<>();
    if (!includeArchived) {
      sql.append(" AND business_status<>'ARCHIVED'");
    }
    if (businessStatus != null) {
      sql.append(" AND business_status=?");
      parameters.add(businessStatus);
    }
    if (attentionState != null) {
      sql.append(" AND attention_state=?");
      parameters.add(attentionState);
    }
    if (targetNodeId != null) {
      sql.append(" AND target_node_id=?");
      parameters.add(targetNodeId);
    }
    sql.append(" ORDER BY updated_at DESC,task_id LIMIT ?");
    parameters.add(limit);
    return jdbc.query(sql.toString(), this::task, parameters.toArray());
  }

  public int updateDefinition(
      String taskId,
      long expectedVersion,
      String title,
      String objective,
      String acceptanceSummary,
      String sideEffectSummary,
      int priority,
      String targetNodeId,
      String workspaceRef,
      String contextRefsJson,
      String runtimeKind,
      String executionProfile,
      String actor,
      Instant now) {
    return jdbc.update(
        """
UPDATE tasks SET title=?,objective=?,acceptance_summary=?,side_effect_summary=?,priority=?,
target_node_id=?,workspace_ref=?,context_refs_json=?,runtime_kind=?,execution_profile=?,
updated_by=?,updated_at=?,version=version+1
WHERE task_id=? AND version=? AND business_status IN ('DRAFT','READY')
""",
        title,
        objective,
        acceptanceSummary,
        sideEffectSummary,
        priority,
        targetNodeId,
        workspaceRef,
        contextRefsJson,
        runtimeKind,
        executionProfile,
        actor,
        now.toString(),
        taskId,
        expectedVersion);
  }

  public int updateState(
      String taskId,
      long expectedVersion,
      String expectedState,
      String businessStatus,
      String acceptanceStatus,
      String attentionState,
      String actor,
      Instant now) {
    return jdbc.update(
        """
UPDATE tasks SET business_status=?,acceptance_status=COALESCE(?,acceptance_status),
attention_state=COALESCE(?,attention_state),updated_by=?,updated_at=?,version=version+1
WHERE task_id=? AND version=? AND business_status=?
""",
        businessStatus,
        acceptanceStatus,
        attentionState,
        actor,
        now.toString(),
        taskId,
        expectedVersion,
        expectedState);
  }

  public int accept(
      String taskId,
      long expectedVersion,
      String deliverySummary,
      String commitSha,
      String prUrl,
      String actor,
      Instant now) {
    return jdbc.update(
        """
UPDATE tasks SET business_status='DONE',acceptance_status='ACCEPTED',attention_state='NONE',
delivery_summary=?,commit_sha=?,pr_url=?,updated_by=?,updated_at=?,version=version+1
WHERE task_id=? AND version=? AND business_status='REVIEW' AND acceptance_status='PENDING'
""",
        deliverySummary,
        commitSha,
        prUrl,
        actor,
        now.toString(),
        taskId,
        expectedVersion);
  }

  public int archive(
      String taskId, long expectedVersion, String expectedState, String actor, Instant now) {
    return jdbc.update(
        """
UPDATE tasks SET archived_from_status=business_status,business_status='ARCHIVED',archived_at=?,
updated_by=?,updated_at=?,version=version+1 WHERE task_id=? AND version=? AND business_status=?
""",
        now.toString(),
        actor,
        now.toString(),
        taskId,
        expectedVersion,
        expectedState);
  }

  public int restore(String taskId, long expectedVersion, String actor, Instant now) {
    return jdbc.update(
        """
UPDATE tasks SET business_status=archived_from_status,archived_from_status=NULL,archived_at=NULL,
updated_by=?,updated_at=?,version=version+1 WHERE task_id=? AND version=?
AND business_status='ARCHIVED' AND archived_from_status IN ('DONE','CANCELLED')
""",
        actor,
        now.toString(),
        taskId,
        expectedVersion);
  }

  public void linkWorkItem(
      String taskId,
      String workItemId,
      long taskVersion,
      String missionId,
      String contextRefsJson,
      Instant now) {
    jdbc.update(
        "INSERT INTO task_work_items(task_id,work_item_id,task_version,created_at) VALUES (?,?,?,?)",
        taskId,
        workItemId,
        taskVersion,
        now.toString());
    jdbc.update(
        "INSERT INTO mission_context_refs(mission_id,context_refs_json) VALUES (?,?)",
        missionId,
        contextRefsJson);
  }

  public Optional<String> findTaskIdByWorkItem(String workItemId) {
    return one(
        "SELECT task_id FROM task_work_items WHERE work_item_id=?",
        (row, rowNumber) -> row.getString(1),
        workItemId);
  }

  public Optional<TaskRunRecord> findActiveRun(String taskId) {
    return one(
        """
SELECT tw.work_item_id,m.mission_id,r.run_id,m.revision,r.status,r.phase_code,
r.progress_summary,r.result_summary,r.last_synced_at,r.created_at
FROM task_work_items tw JOIN missions m ON m.work_item_id=tw.work_item_id
JOIN runs r ON r.mission_id=m.mission_id WHERE tw.task_id=?
AND r.status IN ('assigned','running','waiting_interaction','cancelling')
ORDER BY r.created_at DESC LIMIT 1
""",
        this::taskRun,
        taskId);
  }

  public List<TaskRunRecord> listRuns(String taskId) {
    return jdbc.query(
        """
SELECT tw.work_item_id,m.mission_id,r.run_id,m.revision,r.status,r.phase_code,
r.progress_summary,r.result_summary,r.last_synced_at,r.created_at
FROM task_work_items tw JOIN missions m ON m.work_item_id=tw.work_item_id
JOIN runs r ON r.mission_id=m.mission_id WHERE tw.task_id=?
ORDER BY r.created_at DESC,r.run_id DESC LIMIT 100
""",
        this::taskRun,
        taskId);
  }

  public int nextMissionRevision(String taskId) {
    Integer revision =
        jdbc.queryForObject(
            """
SELECT COALESCE(max(m.revision),0)+1 FROM task_work_items tw
JOIN missions m ON m.work_item_id=tw.work_item_id WHERE tw.task_id=?
""",
            Integer.class,
            taskId);
    return revision == null ? 1 : revision;
  }

  public Optional<NodeProjection> findNode(String nodeId) {
    return one(
        "SELECT node_id,last_heartbeat_at FROM nodes WHERE node_id=?",
        (row, rowNumber) ->
            new NodeProjection(row.getString("node_id"), Instant.parse(row.getString("last_heartbeat_at"))),
        nodeId);
  }

  public void appendEvent(TaskEventRecord event) {
    Long nextSequence =
        jdbc.queryForObject(
            "SELECT COALESCE(max(sequence),0)+1 FROM task_events WHERE task_id=?",
            Long.class,
            event.taskId());
    jdbc.update(
        """
INSERT INTO task_events(event_id,task_id,sequence,event_type,summary,actor,source,work_item_id,
mission_id,run_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
""",
        event.eventId(),
        event.taskId(),
        nextSequence,
        event.eventType(),
        event.summary(),
        event.actor(),
        event.source(),
        event.workItemId(),
        event.missionId(),
        event.runId(),
        event.occurredAt().toString());
  }

  public List<TaskEventRecord> listEvents(String taskId, long afterSequence, int limit) {
    return jdbc.query(
        """
SELECT * FROM task_events WHERE task_id=? AND sequence>? ORDER BY sequence LIMIT ?
""",
        this::taskEvent,
        taskId,
        afterSequence,
        limit);
  }

  public Optional<IdempotencyRecord> findIdempotency(String principal, String key) {
    return one(
        "SELECT operation,request_hash,response_json FROM task_idempotency"
            + " WHERE principal_kind=? AND idempotency_key=?",
        (row, rowNumber) ->
            new IdempotencyRecord(
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
      String requestHash,
      String responseJson,
      Instant now) {
    jdbc.update(
        "INSERT INTO task_idempotency VALUES (?,?,?,?,?,?)",
        principal,
        key,
        operation,
        requestHash,
        responseJson,
        now.toString());
  }

  public void touchExecutionProjection(String taskId, String attentionState, Instant now) {
    jdbc.update(
        """
UPDATE tasks SET attention_state=COALESCE(?,attention_state),updated_at=?,version=version+1
WHERE task_id=? AND business_status NOT IN ('ARCHIVED','DONE','CANCELLED')
""",
        attentionState,
        now.toString(),
        taskId);
  }

  public void projectRunStarted(String taskId, Instant now) {
    jdbc.update(
        """
UPDATE tasks SET business_status='IN_PROGRESS',attention_state='NONE',updated_at=?,version=version+1
WHERE task_id=? AND business_status IN ('READY','IN_PROGRESS')
""",
        now.toString(),
        taskId);
  }

  public void projectRunTerminal(
      String taskId,
      boolean completed,
      String attentionState,
      Instant now) {
    if (completed) {
      jdbc.update(
          """
UPDATE tasks SET business_status='REVIEW',acceptance_status='PENDING',attention_state='NONE',
updated_at=?,version=version+1 WHERE task_id=? AND business_status='IN_PROGRESS'
""",
          now.toString(),
          taskId);
    } else {
      jdbc.update(
          """
UPDATE tasks SET business_status='READY',attention_state=?,updated_at=?,version=version+1
WHERE task_id=? AND business_status='IN_PROGRESS'
""",
          attentionState,
          now.toString(),
          taskId);
    }
  }

  private TaskRecord task(ResultSet row, int rowNumber) throws SQLException {
    return new TaskRecord(
        row.getString("task_id"),
        row.getString("title"),
        row.getString("objective"),
        row.getString("acceptance_summary"),
        row.getString("side_effect_summary"),
        row.getInt("priority"),
        row.getString("target_node_id"),
        row.getString("workspace_ref"),
        row.getString("context_refs_json"),
        row.getString("runtime_kind"),
        row.getString("execution_profile"),
        row.getString("business_status"),
        row.getString("acceptance_status"),
        row.getString("attention_state"),
        row.getString("delivery_summary"),
        row.getString("commit_sha"),
        row.getString("pr_url"),
        row.getLong("version"),
        row.getString("archived_from_status"),
        row.getString("created_by"),
        row.getString("updated_by"),
        Instant.parse(row.getString("created_at")),
        Instant.parse(row.getString("updated_at")),
        instantNullable(row, "archived_at"));
  }

  private TaskRunRecord taskRun(ResultSet row, int rowNumber) throws SQLException {
    return new TaskRunRecord(
        row.getString("work_item_id"),
        row.getString("mission_id"),
        row.getString("run_id"),
        row.getInt("revision"),
        row.getString("status"),
        row.getString("phase_code"),
        row.getString("progress_summary"),
        row.getString("result_summary"),
        instantNullable(row, "last_synced_at"),
        Instant.parse(row.getString("created_at")));
  }

  private TaskEventRecord taskEvent(ResultSet row, int rowNumber) throws SQLException {
    return new TaskEventRecord(
        row.getString("event_id"),
        row.getString("task_id"),
        row.getLong("sequence"),
        row.getString("event_type"),
        row.getString("summary"),
        row.getString("actor"),
        row.getString("source"),
        row.getString("work_item_id"),
        row.getString("mission_id"),
        row.getString("run_id"),
        Instant.parse(row.getString("occurred_at")));
  }

  private Instant instantNullable(ResultSet row, String column) throws SQLException {
    String value = row.getString(column);
    return value == null ? null : Instant.parse(value);
  }

  private <T> Optional<T> one(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... arguments) {
    return jdbc.query(sql, mapper, arguments).stream().findFirst();
  }

  public record TaskRecord(
      String taskId,
      String title,
      String objective,
      String acceptanceSummary,
      String sideEffectSummary,
      int priority,
      String targetNodeId,
      String workspaceRef,
      String contextRefsJson,
      String runtimeKind,
      String executionProfile,
      String businessStatus,
      String acceptanceStatus,
      String attentionState,
      String deliverySummary,
      String commitSha,
      String prUrl,
      long version,
      String archivedFromStatus,
      String createdBy,
      String updatedBy,
      Instant createdAt,
      Instant updatedAt,
      Instant archivedAt) {}

  public record TaskRunRecord(
      String workItemId,
      String missionId,
      String runId,
      int missionRevision,
      String status,
      String phaseCode,
      String progressSummary,
      String resultSummary,
      Instant lastObservedAt,
      Instant createdAt) {}

  public record TaskEventRecord(
      String eventId,
      String taskId,
      long sequence,
      String eventType,
      String summary,
      String actor,
      String source,
      String workItemId,
      String missionId,
      String runId,
      Instant occurredAt) {
    public TaskEventRecord(
        String eventId,
        String taskId,
        String eventType,
        String summary,
        String actor,
        String source,
        String workItemId,
        String missionId,
        String runId,
        Instant occurredAt) {
      this(
          eventId,
          taskId,
          0,
          eventType,
          summary,
          actor,
          source,
          workItemId,
          missionId,
          runId,
          occurredAt);
    }
  }

  public record NodeProjection(String nodeId, Instant lastHeartbeatAt) {}

  public record IdempotencyRecord(String operation, String requestHash, String responseJson) {}
}
