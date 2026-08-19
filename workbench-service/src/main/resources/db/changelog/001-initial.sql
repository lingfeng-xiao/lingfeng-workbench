--liquibase formatted sql

--changeset workbench:001-initial runInTransaction:false
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA busy_timeout = 5000;

CREATE TABLE work_items (
    work_item_id TEXT PRIMARY KEY,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 800),
    status TEXT NOT NULL,
    priority INTEGER NOT NULL CHECK(priority BETWEEN -100 AND 100),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE missions (
    mission_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES work_items(work_item_id),
    revision INTEGER NOT NULL CHECK(revision >= 1),
    mission_digest TEXT NOT NULL CHECK(length(mission_digest) = 64),
    objective TEXT NOT NULL CHECK(length(objective) BETWEEN 1 AND 800),
    acceptance_summary TEXT NOT NULL CHECK(length(acceptance_summary) BETWEEN 1 AND 800),
    authorized_side_effects_summary TEXT NOT NULL CHECK(length(authorized_side_effects_summary) BETWEEN 1 AND 800),
    target_node_id TEXT NOT NULL,
    workspace_ref TEXT NOT NULL,
    runtime_kind TEXT NOT NULL,
    execution_profile TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE runs (
    run_id TEXT PRIMARY KEY,
    mission_id TEXT NOT NULL REFERENCES missions(mission_id),
    node_id TEXT NOT NULL,
    command_id TEXT NOT NULL UNIQUE,
    command_acknowledged_at TEXT,
    status TEXT NOT NULL,
    progress_summary TEXT,
    result_summary TEXT,
    resumable INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE nodes (
    node_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 800),
    capabilities_json TEXT NOT NULL,
    last_heartbeat_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE timeline_events (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(run_id),
    event_type TEXT NOT NULL,
    summary TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE interactions (
    interaction_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(run_id),
    checkpoint_id TEXT NOT NULL,
    mission_digest TEXT NOT NULL,
    state TEXT NOT NULL,
    prompt_summary TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE idempotency_records (
    idempotency_key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    work_item_id TEXT NOT NULL REFERENCES work_items(work_item_id),
    mission_id TEXT NOT NULL REFERENCES missions(mission_id),
    mission_digest TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE node_messages (
    message_id TEXT PRIMARY KEY,
    node_id TEXT NOT NULL,
    message_type TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_missions_target_status ON missions(target_node_id, status, created_at);
CREATE INDEX idx_runs_mission ON runs(mission_id, created_at);
CREATE INDEX idx_runs_node_status ON runs(node_id, status, created_at);
CREATE INDEX idx_timeline_run ON timeline_events(run_id, created_at);
CREATE INDEX idx_interactions_state ON interactions(state, created_at);

--rollback DROP TABLE interactions; DROP TABLE timeline_events; DROP TABLE node_messages; DROP TABLE idempotency_records; DROP TABLE runs; DROP TABLE missions; DROP TABLE nodes; DROP TABLE work_items;
