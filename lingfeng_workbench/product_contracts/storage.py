"""SQLite adapter used only for isolated contract validation and temporary stores."""

from __future__ import annotations

import json
import sqlite3
from typing import Any

from .enums import ActorRole, DecisionOutcome, GateKind, ObjectType
from .models import ContractObject, Decision, contract_from_dict
from .rules import GATE_TARGETS
from .validation import identifier


class SqliteContractStore:
    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS contract_objects (
                object_type TEXT NOT NULL,
                object_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                space TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                PRIMARY KEY (object_type, object_id, version)
            )
            """
        )

    def append(self, record: ContractObject) -> None:
        payload = json.dumps(
            record.to_dict(),
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        try:
            with self.connection:
                self.connection.execute(
                    """
                    INSERT INTO contract_objects
                        (object_type, object_id, version, space, payload_json)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        record.object_type.value,
                        record.id,
                        record.version,
                        record.space.value,
                        payload,
                    ),
                )
        except sqlite3.IntegrityError as exc:
            raise ValueError("object versions are append-only") from exc

    def get(
        self,
        object_type: ObjectType,
        object_id: str,
        version: int | None = None,
    ) -> ContractObject:
        object_id = identifier(object_id, "object_id")
        if version is None:
            row = self.connection.execute(
                """
                SELECT payload_json
                FROM contract_objects
                WHERE object_type = ? AND object_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
                (ObjectType(object_type).value, object_id),
            ).fetchone()
        else:
            row = self.connection.execute(
                """
                SELECT payload_json
                FROM contract_objects
                WHERE object_type = ? AND object_id = ? AND version = ?
                """,
                (ObjectType(object_type).value, object_id, version),
            ).fetchone()
        if row is None:
            raise KeyError(f"{ObjectType(object_type).value}/{object_id} was not found")
        return contract_from_dict(json.loads(row[0]))


class GateLedger:
    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection
        self.connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS gate_decisions (
                decision_id TEXT PRIMARY KEY,
                replay_key TEXT NOT NULL UNIQUE,
                gate TEXT NOT NULL,
                target_type TEXT NOT NULL,
                target_id TEXT NOT NULL,
                target_version INTEGER NOT NULL,
                scope TEXT NOT NULL,
                outcome TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                UNIQUE (gate, target_type, target_id, target_version, scope)
            );
            CREATE TABLE IF NOT EXISTS gate_consumptions (
                decision_id TEXT PRIMARY KEY,
                action_id TEXT NOT NULL UNIQUE,
                FOREIGN KEY (decision_id) REFERENCES gate_decisions(decision_id)
            );
            """
        )

    def record(self, decision: Decision, *, current_target_version: int) -> None:
        if decision.decider_role is not ActorRole.USER:
            raise PermissionError("a machine or Workbench cannot authorize itself")
        if decision.target_version != current_target_version:
            raise PermissionError("stale Gate decision")
        expected_type = GATE_TARGETS.get(decision.gate)
        if expected_type is not None and decision.target_type is not expected_type:
            raise PermissionError("Gate targets the wrong object type")
        payload = json.dumps(
            decision.to_dict(),
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        try:
            with self.connection:
                self.connection.execute(
                    """
                    INSERT INTO gate_decisions (
                        decision_id, replay_key, gate, target_type, target_id,
                        target_version, scope, outcome, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        decision.id,
                        decision.replay_key,
                        decision.gate.value,
                        decision.target_type.value,
                        decision.target_id,
                        decision.target_version,
                        decision.scope,
                        decision.outcome.value,
                        payload,
                    ),
                )
        except sqlite3.IntegrityError as exc:
            raise PermissionError("Gate decision replay or duplicate authorization") from exc

    def consume(
        self,
        decision_id: str,
        *,
        action_id: str,
        gate: GateKind,
        target_type: ObjectType,
        target_id: str,
        target_version: int,
        scope: str,
    ) -> None:
        decision_id = identifier(decision_id, "decision_id")
        action_id = identifier(action_id, "action_id")
        row = self.connection.execute(
            """
            SELECT gate, target_type, target_id, target_version, scope, outcome
            FROM gate_decisions
            WHERE decision_id = ?
            """,
            (decision_id,),
        ).fetchone()
        expected: tuple[Any, ...] = (
            GateKind(gate).value,
            ObjectType(target_type).value,
            identifier(target_id, "target_id"),
            target_version,
            scope,
            DecisionOutcome.ACCEPT.value,
        )
        if row is None or tuple(row) != expected:
            raise PermissionError("Gate decision does not authorize this exact action")
        try:
            with self.connection:
                self.connection.execute(
                    """
                    INSERT INTO gate_consumptions (decision_id, action_id)
                    VALUES (?, ?)
                    """,
                    (decision_id, action_id),
                )
        except sqlite3.IntegrityError as exc:
            raise PermissionError("Gate decision cannot be replayed") from exc
