--liquibase formatted sql

--changeset workbench:003-task-business-loop
CREATE TABLE tasks (
    task_id TEXT PRIMARY KEY,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 200),
    objective TEXT NOT NULL CHECK(length(objective) BETWEEN 1 AND 800),
    acceptance_summary TEXT NOT NULL CHECK(length(acceptance_summary) BETWEEN 1 AND 800),
    side_effect_summary TEXT NOT NULL CHECK(length(side_effect_summary) BETWEEN 1 AND 800),
    priority INTEGER NOT NULL CHECK(priority BETWEEN -100 AND 100),
    target_node_id TEXT NOT NULL CHECK(length(target_node_id) BETWEEN 1 AND 128),
    workspace_ref TEXT NOT NULL CHECK(length(workspace_ref) BETWEEN 1 AND 128),
    context_refs_json TEXT NOT NULL,
    runtime_kind TEXT NOT NULL CHECK(length(runtime_kind) BETWEEN 1 AND 128),
    execution_profile TEXT NOT NULL CHECK(length(execution_profile) BETWEEN 1 AND 128),
    business_status TEXT NOT NULL CHECK(business_status IN
        ('DRAFT','READY','IN_PROGRESS','REVIEW','DONE','ARCHIVED','CANCELLED')),
    acceptance_status TEXT NOT NULL CHECK(acceptance_status IN
        ('NOT_REQUESTED','PENDING','ACCEPTED','CHANGES_REQUESTED')),
    attention_state TEXT NOT NULL CHECK(attention_state IN
        ('NONE','WAITING_INPUT','APPROVAL_REQUIRED','RUN_FAILED','RUN_UNCERTAIN','NODE_OFFLINE','STALE')),
    delivery_summary TEXT CHECK(delivery_summary IS NULL OR length(delivery_summary) BETWEEN 1 AND 800),
    commit_sha TEXT CHECK(commit_sha IS NULL OR length(commit_sha) BETWEEN 7 AND 64),
    pr_url TEXT CHECK(pr_url IS NULL OR length(pr_url) BETWEEN 1 AND 800),
    version INTEGER NOT NULL CHECK(version >= 1),
    archived_from_status TEXT,
    created_by TEXT NOT NULL,
    updated_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    archived_at TEXT
);

CREATE TABLE task_work_items (
    task_id TEXT NOT NULL REFERENCES tasks(task_id),
    work_item_id TEXT NOT NULL UNIQUE REFERENCES work_items(work_item_id),
    task_version INTEGER NOT NULL CHECK(task_version >= 1),
    created_at TEXT NOT NULL,
    PRIMARY KEY(task_id, work_item_id)
);

CREATE TABLE mission_context_refs (
    mission_id TEXT PRIMARY KEY REFERENCES missions(mission_id),
    context_refs_json TEXT NOT NULL
);

CREATE TABLE task_events (
    event_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(task_id),
    sequence INTEGER NOT NULL CHECK(sequence >= 1),
    event_type TEXT NOT NULL,
    summary TEXT NOT NULL CHECK(length(summary) BETWEEN 1 AND 800),
    actor TEXT NOT NULL,
    source TEXT NOT NULL CHECK(source IN ('USER','SERVICE','NODE')),
    work_item_id TEXT REFERENCES work_items(work_item_id),
    mission_id TEXT REFERENCES missions(mission_id),
    run_id TEXT REFERENCES runs(run_id),
    occurred_at TEXT NOT NULL,
    UNIQUE(task_id, sequence)
);

CREATE TABLE task_idempotency (
    principal_kind TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK(length(request_hash) = 64),
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(principal_kind, idempotency_key)
);

CREATE INDEX idx_tasks_activity ON tasks(business_status, attention_state, updated_at);
CREATE INDEX idx_tasks_node ON tasks(target_node_id, updated_at);
CREATE INDEX idx_task_events_history ON task_events(task_id, sequence);
CREATE INDEX idx_task_work_items_history ON task_work_items(task_id, created_at);
