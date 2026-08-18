"""Plugin-owned SQLite state; Kanban remains the generic task repository."""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from .domain import (
    BindingKind,
    InteractionKind,
    InteractionState,
    MissionAssignment,
    RunState,
    SideEffectState,
    new_identifier,
    utc_now_iso,
)


SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS nodes (
    node_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    protocol_version TEXT NOT NULL,
    runtime_capabilities TEXT NOT NULL,
    connected_at TEXT NOT NULL,
    last_heartbeat_at TEXT NOT NULL,
    last_error_summary TEXT
);

CREATE TABLE IF NOT EXISTS task_bindings (
    binding_id TEXT PRIMARY KEY,
    work_item_id TEXT NOT NULL,
    mission_id TEXT,
    kanban_task_id TEXT NOT NULL UNIQUE,
    binding_kind TEXT NOT NULL,
    target_node_id TEXT NOT NULL,
    runtime_kind TEXT NOT NULL,
    local_workspace_ref TEXT NOT NULL,
    objective TEXT NOT NULL,
    acceptance_summary TEXT NOT NULL,
    interaction_policy TEXT NOT NULL DEFAULT 'ask_when_blocked',
    authorized_side_effects_summary TEXT NOT NULL DEFAULT 'none',
    data_boundary_ack_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (mission_id)
);

CREATE INDEX IF NOT EXISTS idx_task_bindings_node_kind
    ON task_bindings(target_node_id, binding_kind);

CREATE TABLE IF NOT EXISTS runs (
    run_id TEXT PRIMARY KEY,
    mission_id TEXT NOT NULL,
    kanban_task_id TEXT NOT NULL,
    kanban_run_id INTEGER,
    node_id TEXT NOT NULL,
    runtime_kind TEXT NOT NULL,
    runtime_session_ref TEXT,
    state TEXT NOT NULL,
    checkpoint_id TEXT,
    progress_summary TEXT,
    result_summary TEXT,
    side_effect_state TEXT NOT NULL,
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    finished_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_runs_mission_state
    ON runs(mission_id, state);

CREATE TABLE IF NOT EXISTS interactions (
    interaction_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    checkpoint_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    prompt_summary TEXT NOT NULL,
    options_json TEXT NOT NULL,
    risk_summary TEXT,
    state TEXT NOT NULL,
    expires_at TEXT,
    response_summary TEXT,
    created_at TEXT NOT NULL,
    resolved_at TEXT,
    delivered_at TEXT,
    FOREIGN KEY (run_id) REFERENCES runs(run_id)
);

CREATE INDEX IF NOT EXISTS idx_interactions_state
    ON interactions(state, created_at);

CREATE TABLE IF NOT EXISTS control_events (
    event_id TEXT PRIMARY KEY,
    message_id TEXT UNIQUE,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    summary TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (operation, idempotency_key)
);
"""


TERMINAL_RUN_STATES = tuple(
    state.value for state in RunState if state.is_terminal
)


class PluginStore:
    """Small transactional store for lingfeng-specific control state."""

    def __init__(self, database_path: Path) -> None:
        self.database_path = Path(database_path)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(SCHEMA)
            interaction_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(interactions)")
            }
            if "delivered_at" not in interaction_columns:
                connection.execute(
                    "ALTER TABLE interactions ADD COLUMN delivered_at TEXT"
                )
            binding_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(task_bindings)")
            }
            if "interaction_policy" not in binding_columns:
                connection.execute(
                    """
                    ALTER TABLE task_bindings ADD COLUMN interaction_policy TEXT
                    NOT NULL DEFAULT 'ask_when_blocked'
                    """
                )
            if "authorized_side_effects_summary" not in binding_columns:
                connection.execute(
                    """
                    ALTER TABLE task_bindings
                    ADD COLUMN authorized_side_effects_summary TEXT
                    NOT NULL DEFAULT 'none'
                    """
                )

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database_path, timeout=5.0)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA foreign_keys=ON")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def register_node(
        self,
        *,
        node_id: str,
        display_name: str,
        protocol_version: str,
        runtime_capabilities: dict[str, Any],
    ) -> None:
        now = utc_now_iso()
        encoded_capabilities = json.dumps(
            runtime_capabilities,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO nodes (
                    node_id, display_name, status, protocol_version,
                    runtime_capabilities, connected_at, last_heartbeat_at
                ) VALUES (?, ?, 'online', ?, ?, ?, ?)
                ON CONFLICT(node_id) DO UPDATE SET
                    display_name = excluded.display_name,
                    status = 'online',
                    protocol_version = excluded.protocol_version,
                    runtime_capabilities = excluded.runtime_capabilities,
                    connected_at = excluded.connected_at,
                    last_heartbeat_at = excluded.last_heartbeat_at,
                    last_error_summary = NULL
                """,
                (
                    node_id,
                    display_name,
                    protocol_version,
                    encoded_capabilities,
                    now,
                    now,
                ),
            )

    def heartbeat_node(self, node_id: str) -> bool:
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE nodes
                   SET status = 'online', last_heartbeat_at = ?
                 WHERE node_id = ?
                """,
                (utc_now_iso(), node_id),
            )
            return cursor.rowcount == 1

    def bind_work_item(
        self,
        *,
        work_item_id: str,
        kanban_task_id: str,
        target_node_id: str,
        local_workspace_ref: str,
        objective: str,
        acceptance_summary: str,
        interaction_policy: str = "ask_when_blocked",
        authorized_side_effects_summary: str = "none",
    ) -> None:
        self._insert_binding(
            binding_id=new_identifier("bind"),
            work_item_id=work_item_id,
            mission_id=None,
            kanban_task_id=kanban_task_id,
            binding_kind=BindingKind.WORK_ITEM,
            target_node_id=target_node_id,
            local_workspace_ref=local_workspace_ref,
            objective=objective,
            acceptance_summary=acceptance_summary,
            interaction_policy=interaction_policy,
            authorized_side_effects_summary=authorized_side_effects_summary,
        )

    def bind_mission(
        self,
        *,
        work_item_id: str,
        mission_id: str,
        kanban_task_id: str,
        target_node_id: str,
        local_workspace_ref: str,
        objective: str,
        acceptance_summary: str,
        interaction_policy: str = "ask_when_blocked",
        authorized_side_effects_summary: str = "none",
    ) -> None:
        self._insert_binding(
            binding_id=new_identifier("bind"),
            work_item_id=work_item_id,
            mission_id=mission_id,
            kanban_task_id=kanban_task_id,
            binding_kind=BindingKind.MISSION,
            target_node_id=target_node_id,
            local_workspace_ref=local_workspace_ref,
            objective=objective,
            acceptance_summary=acceptance_summary,
            interaction_policy=interaction_policy,
            authorized_side_effects_summary=authorized_side_effects_summary,
        )

    def _insert_binding(
        self,
        *,
        binding_id: str,
        work_item_id: str,
        mission_id: str | None,
        kanban_task_id: str,
        binding_kind: BindingKind,
        target_node_id: str,
        local_workspace_ref: str,
        objective: str,
        acceptance_summary: str,
        interaction_policy: str,
        authorized_side_effects_summary: str,
    ) -> None:
        now = utc_now_iso()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO task_bindings (
                    binding_id, work_item_id, mission_id, kanban_task_id,
                    binding_kind, target_node_id, runtime_kind,
                    local_workspace_ref, objective, acceptance_summary,
                    interaction_policy, authorized_side_effects_summary,
                    data_boundary_ack_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ws', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    binding_id,
                    work_item_id,
                    mission_id,
                    kanban_task_id,
                    binding_kind.value,
                    target_node_id,
                    local_workspace_ref,
                    objective,
                    acceptance_summary,
                    interaction_policy,
                    authorized_side_effects_summary,
                    now,
                    now,
                    now,
                ),
            )
            if cursor.rowcount == 0:
                existing = connection.execute(
                    """
                    SELECT work_item_id, mission_id, kanban_task_id,
                           binding_kind, target_node_id, local_workspace_ref,
                           objective, acceptance_summary, interaction_policy,
                           authorized_side_effects_summary
                      FROM task_bindings
                     WHERE kanban_task_id = ?
                        OR (? IS NOT NULL AND mission_id = ?)
                    """,
                    (kanban_task_id, mission_id, mission_id),
                ).fetchone()
                expected = (
                    work_item_id,
                    mission_id,
                    kanban_task_id,
                    binding_kind.value,
                    target_node_id,
                    local_workspace_ref,
                    objective,
                    acceptance_summary,
                    interaction_policy,
                    authorized_side_effects_summary,
                )
                if existing is None or tuple(existing) != expected:
                    raise RuntimeError("binding idempotency conflict")

    def list_mission_bindings(self, target_node_id: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT *
                  FROM task_bindings
                 WHERE target_node_id = ?
                   AND binding_kind = ?
                 ORDER BY created_at ASC
                """,
                (target_node_id, BindingKind.MISSION.value),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_binding_by_work_item(self, work_item_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT * FROM task_bindings
                 WHERE work_item_id = ? AND binding_kind = ?
                 LIMIT 1
                """,
                (work_item_id, BindingKind.WORK_ITEM.value),
            ).fetchone()
            return dict(row) if row else None

    def get_binding_by_mission(self, mission_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM task_bindings WHERE mission_id = ?",
                (mission_id,),
            ).fetchone()
            return dict(row) if row else None

    def create_run(
        self,
        *,
        mission_id: str,
        kanban_task_id: str,
        kanban_run_id: int | None,
        node_id: str,
        runtime_kind: str,
    ) -> str:
        run_id = new_identifier("run")
        now = utc_now_iso()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO runs (
                    run_id, mission_id, kanban_task_id, kanban_run_id,
                    node_id, runtime_kind, state, side_effect_state,
                    started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run_id,
                    mission_id,
                    kanban_task_id,
                    kanban_run_id,
                    node_id,
                    runtime_kind,
                    RunState.ASSIGNED.value,
                    SideEffectState.NONE.value,
                    now,
                    now,
                ),
            )
        return run_id

    def update_run(
        self,
        run_id: str,
        *,
        state: RunState,
        checkpoint_id: str | None = None,
        progress_summary: str | None = None,
        result_summary: str | None = None,
        runtime_session_ref: str | None = None,
        side_effect_state: SideEffectState | None = None,
    ) -> bool:
        assignments: list[str] = ["state = ?", "updated_at = ?"]
        values: list[Any] = [state.value, utc_now_iso()]
        optional_values = {
            "checkpoint_id": checkpoint_id,
            "progress_summary": progress_summary,
            "result_summary": result_summary,
            "runtime_session_ref": runtime_session_ref,
            "side_effect_state": side_effect_state.value if side_effect_state else None,
        }
        for column, value in optional_values.items():
            if value is not None:
                assignments.append(f"{column} = ?")
                values.append(value)
        if state.is_terminal:
            assignments.append("finished_at = ?")
            values.append(utc_now_iso())
        values.append(run_id)
        with self._connect() as connection:
            cursor = connection.execute(
                f"UPDATE runs SET {', '.join(assignments)} WHERE run_id = ?",
                values,
            )
            return cursor.rowcount == 1

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM runs WHERE run_id = ?",
                (run_id,),
            ).fetchone()
            return dict(row) if row else None

    def get_active_run_for_mission(self, mission_id: str) -> dict[str, Any] | None:
        placeholders = ",".join("?" for _ in TERMINAL_RUN_STATES)
        with self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT * FROM runs
                 WHERE mission_id = ?
                   AND state NOT IN ({placeholders})
                 ORDER BY started_at DESC
                 LIMIT 1
                """,
                (mission_id, *TERMINAL_RUN_STATES),
            ).fetchone()
            return dict(row) if row else None

    def create_interaction(
        self,
        *,
        run_id: str,
        checkpoint_id: str,
        kind: InteractionKind,
        prompt_summary: str,
        options: list[str],
        risk_summary: str | None,
        expires_at: str | None,
    ) -> str:
        interaction_id = new_identifier("ix")
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO interactions (
                    interaction_id, run_id, checkpoint_id, kind,
                    prompt_summary, options_json, risk_summary, state,
                    expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    interaction_id,
                    run_id,
                    checkpoint_id,
                    kind.value,
                    prompt_summary,
                    json.dumps(options, ensure_ascii=False),
                    risk_summary,
                    InteractionState.PENDING.value,
                    expires_at,
                    utc_now_iso(),
                ),
            )
        return interaction_id

    def resolve_interaction(self, interaction_id: str, response_summary: str) -> bool:
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE interactions
                   SET state = ?, response_summary = ?, resolved_at = ?
                 WHERE interaction_id = ? AND state = ?
                """,
                (
                    InteractionState.RESOLVED.value,
                    response_summary,
                    utc_now_iso(),
                    interaction_id,
                    InteractionState.PENDING.value,
                ),
            )
            return cursor.rowcount == 1

    def list_pending_interactions(self) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM interactions
                 WHERE state = ?
                 ORDER BY created_at ASC
                """,
                (InteractionState.PENDING.value,),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_interaction(self, interaction_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM interactions WHERE interaction_id = ?",
                (interaction_id,),
            ).fetchone()
            return dict(row) if row else None

    def take_resolved_interaction_for_node(
        self,
        node_id: str,
    ) -> dict[str, Any] | None:
        """Atomically mark and return one response awaiting node delivery."""
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT i.*
                  FROM interactions i
                  JOIN runs r ON r.run_id = i.run_id
                 WHERE r.node_id = ?
                   AND i.state = ?
                   AND i.delivered_at IS NULL
                 ORDER BY i.resolved_at ASC
                 LIMIT 1
                """,
                (node_id, InteractionState.RESOLVED.value),
            ).fetchone()
            if row is None:
                return None
            delivered_at = utc_now_iso()
            cursor = connection.execute(
                """
                UPDATE interactions SET delivered_at = ?
                 WHERE interaction_id = ? AND delivered_at IS NULL
                """,
                (delivered_at, row["interaction_id"]),
            )
            if cursor.rowcount != 1:
                return None
            result = dict(row)
            result["delivered_at"] = delivered_at
            return result

    def get_idempotent_result(
        self,
        operation: str,
        idempotency_key: str,
    ) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT result_json FROM idempotency_records
                 WHERE operation = ? AND idempotency_key = ?
                """,
                (operation, idempotency_key),
            ).fetchone()
        return json.loads(row["result_json"]) if row else None

    def record_idempotent_result(
        self,
        operation: str,
        idempotency_key: str,
        result: dict[str, Any],
    ) -> dict[str, Any]:
        encoded = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO idempotency_records (
                    operation, idempotency_key, result_json, created_at
                ) VALUES (?, ?, ?, ?)
                """,
                (operation, idempotency_key, encoded, utc_now_iso()),
            )
            row = connection.execute(
                """
                SELECT result_json FROM idempotency_records
                 WHERE operation = ? AND idempotency_key = ?
                """,
                (operation, idempotency_key),
            ).fetchone()
        if row is None:  # pragma: no cover - SQLite invariant
            raise RuntimeError("failed to persist idempotency record")
        return json.loads(row["result_json"])

    def has_control_event(self, message_id: str) -> bool:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM control_events WHERE message_id = ?",
                (message_id,),
            ).fetchone()
            return row is not None

    def record_control_event_once(
        self,
        *,
        message_id: str,
        entity_type: str,
        entity_id: str,
        event_type: str,
        summary: str | None,
    ) -> bool:
        try:
            with self._connect() as connection:
                connection.execute(
                    """
                    INSERT INTO control_events (
                        event_id, message_id, entity_type, entity_id,
                        event_type, summary, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        new_identifier("evt"),
                        message_id,
                        entity_type,
                        entity_id,
                        event_type,
                        summary,
                        utc_now_iso(),
                    ),
                )
            return True
        except sqlite3.IntegrityError:
            return False

    def status_snapshot(self) -> dict[str, Any]:
        with self._connect() as connection:
            nodes = [dict(row) for row in connection.execute(
                """
                SELECT node_id, display_name, status, protocol_version,
                       last_heartbeat_at, last_error_summary
                  FROM nodes ORDER BY node_id
                """
            ).fetchall()]
            runs = [dict(row) for row in connection.execute(
                """
                SELECT run_id, mission_id, node_id, runtime_kind, state,
                       progress_summary, result_summary, updated_at
                  FROM runs ORDER BY started_at DESC LIMIT 20
                """
            ).fetchall()]
            pending = [dict(row) for row in connection.execute(
                """
                SELECT interaction_id, run_id, kind, prompt_summary, created_at
                  FROM interactions WHERE state = ? ORDER BY created_at
                """,
                (InteractionState.PENDING.value,),
            ).fetchall()]
            counts = {
                "work_items": connection.execute(
                    "SELECT COUNT(*) FROM task_bindings WHERE binding_kind = ?",
                    (BindingKind.WORK_ITEM.value,),
                ).fetchone()[0],
                "missions": connection.execute(
                    "SELECT COUNT(*) FROM task_bindings WHERE binding_kind = ?",
                    (BindingKind.MISSION.value,),
                ).fetchone()[0],
            }
        return {
            "nodes": nodes,
            "recent_runs": runs,
            "pending_interactions": pending,
            "counts": counts,
        }

    def assignment_from_binding(
        self,
        binding: dict[str, Any],
        *,
        run_id: str,
    ) -> MissionAssignment:
        return MissionAssignment(
            work_item_id=binding["work_item_id"],
            mission_id=binding["mission_id"],
            kanban_task_id=binding["kanban_task_id"],
            run_id=run_id,
            objective=binding["objective"],
            acceptance_summary=binding["acceptance_summary"],
            target_node_id=binding["target_node_id"],
            local_workspace_ref=binding["local_workspace_ref"],
            runtime_kind=binding["runtime_kind"],
            interaction_policy=binding["interaction_policy"],
            authorized_side_effects_summary=binding[
                "authorized_side_effects_summary"
            ],
        )
