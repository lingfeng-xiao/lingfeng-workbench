--liquibase formatted sql

--changeset workbench:002-control-loop-v2
ALTER TABLE runs ADD COLUMN phase_code TEXT;
ALTER TABLE runs ADD COLUMN last_synced_at TEXT;

ALTER TABLE interactions ADD COLUMN target_node_id TEXT;
ALTER TABLE interactions ADD COLUMN allowed_decisions_json TEXT;
ALTER TABLE interactions ADD COLUMN response_summary TEXT;
ALTER TABLE interactions ADD COLUMN resolved_at TEXT;
ALTER TABLE interactions ADD COLUMN consumed_at TEXT;
ALTER TABLE interactions ADD COLUMN response_command_id TEXT;

CREATE TABLE control_commands (
    command_id TEXT PRIMARY KEY,
    target_node_id TEXT NOT NULL,
    work_item_id TEXT NOT NULL REFERENCES work_items(work_item_id),
    mission_id TEXT NOT NULL REFERENCES missions(mission_id),
    run_id TEXT NOT NULL REFERENCES runs(run_id),
    mission_digest TEXT NOT NULL CHECK(length(mission_digest) = 64),
    command_type TEXT NOT NULL CHECK(command_type IN ('START_RUN','PROVIDE_INTERACTION_RESPONSE','CANCEL_RUN')),
    payload_json TEXT NOT NULL,
    payload_digest TEXT NOT NULL CHECK(length(payload_digest) = 64),
    interaction_id TEXT REFERENCES interactions(interaction_id),
    created_at TEXT NOT NULL,
    acknowledged_at TEXT
);

CREATE TABLE client_idempotency_v2 (
    principal_kind TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK(length(request_hash) = 64),
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(principal_kind, idempotency_key)
);

CREATE TABLE node_event_dedup_v2 (
    node_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload_hash TEXT NOT NULL CHECK(length(payload_hash) = 64),
    created_at TEXT NOT NULL,
    PRIMARY KEY(node_id, message_id)
);

CREATE TABLE notification_delivery (
    notification_id TEXT PRIMARY KEY,
    dedup_key TEXT NOT NULL UNIQUE,
    notification_type TEXT NOT NULL,
    target_alias TEXT NOT NULL CHECK(target_alias = 'owner'),
    work_item_id TEXT NOT NULL REFERENCES work_items(work_item_id),
    mission_id TEXT REFERENCES missions(mission_id),
    run_id TEXT REFERENCES runs(run_id),
    interaction_id TEXT REFERENCES interactions(interaction_id),
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 800),
    message_summary TEXT NOT NULL CHECK(length(message_summary) BETWEEN 1 AND 800),
    status TEXT NOT NULL CHECK(status IN ('pending','leased','delivered','dead_letter')),
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_expires_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE notification_delivery_event (
    notification_id TEXT NOT NULL REFERENCES notification_delivery(notification_id),
    delivery_event_id TEXT NOT NULL,
    payload_hash TEXT NOT NULL CHECK(length(payload_hash) = 64),
    outcome TEXT NOT NULL CHECK(outcome IN ('DELIVERED','FAILED')),
    reported_at TEXT NOT NULL,
    PRIMARY KEY(notification_id, delivery_event_id)
);

CREATE UNIQUE INDEX idx_interaction_run_checkpoint_v2 ON interactions(run_id, checkpoint_id);
CREATE INDEX idx_control_command_poll_v2 ON control_commands(target_node_id, acknowledged_at, created_at);
CREATE INDEX idx_notification_poll_v2 ON notification_delivery(target_alias, status, created_at);
CREATE INDEX idx_runs_last_synced_v2 ON runs(last_synced_at);
