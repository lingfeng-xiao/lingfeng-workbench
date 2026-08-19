"""Isolated in-memory SQLite contract verifier; never a cloud fact-source adapter."""

from __future__ import annotations

import json
import sqlite3
from typing import Any

from .api import authorize_operation
from .auth import AuthenticatedPrincipal, require_authenticated
from .enums import (
    ActorRole,
    CloudSafeKind,
    DataClass,
    DecisionOutcome,
    GateKind,
    ObjectType,
    ProposalState,
    ReleaseState,
)
from .models import (
    AgentRuntime,
    ArtifactReference,
    Capability,
    ContractObject,
    ControlEvent,
    Decision,
    Interaction,
    Mission,
    Proposal,
    Release,
    Run,
    WorkItem,
    contract_from_persisted_dict,
    record_hash,
)
from .rules import (
    ArtifactCandidate,
    CrossSpaceReference,
    GATE_TARGETS,
    classify_artifact_candidate,
    gate_for_transition,
    validate_transition,
)
from .validation import identifier


class IsolatedSqliteContractStore:
    """Temporary test store. D1 remains the cloud control-plane fact source."""

    def __init__(self, connection: sqlite3.Connection, *, isolated: bool) -> None:
        if not isolated:
            raise RuntimeError("SQLite contract store is restricted to isolated tests")
        database_path = connection.execute("PRAGMA database_list").fetchone()[2]
        if database_path:
            raise RuntimeError("SQLite contract store must use an in-memory database")
        self.connection = connection
        self.connection.execute("PRAGMA foreign_keys = ON")
        if self.connection.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
            raise RuntimeError("foreign-key enforcement is required")
        self.connection.executescript(
            """
            CREATE TABLE contract_objects (
                object_type TEXT NOT NULL,
                object_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                space TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                record_hash TEXT NOT NULL,
                PRIMARY KEY (object_type, object_id, version),
                UNIQUE (object_type, object_id, record_hash)
            );
            CREATE TABLE cross_space_references (
                reference_id TEXT PRIMARY KEY,
                source_type TEXT NOT NULL,
                source_id TEXT NOT NULL,
                target_type TEXT NOT NULL,
                target_id TEXT NOT NULL,
                control_event_id TEXT NOT NULL
            );
            CREATE TABLE gate_decisions (
                decision_id TEXT PRIMARY KEY,
                replay_key TEXT NOT NULL UNIQUE,
                gate TEXT NOT NULL,
                target_type TEXT NOT NULL,
                target_id TEXT NOT NULL,
                target_version INTEGER NOT NULL,
                target_hash TEXT NOT NULL,
                scope TEXT NOT NULL,
                outcome TEXT NOT NULL,
                decider_id TEXT NOT NULL,
                UNIQUE (gate, target_type, target_id, target_version, target_hash, scope)
            );
            CREATE TABLE gate_consumptions (
                decision_id TEXT PRIMARY KEY,
                action_id TEXT NOT NULL UNIQUE,
                consumed_target_hash TEXT NOT NULL,
                FOREIGN KEY (decision_id) REFERENCES gate_decisions(decision_id)
            );
            CREATE UNIQUE INDEX control_event_sequence
            ON contract_objects (
                json_extract(payload_json, '$.subject_type'),
                json_extract(payload_json, '$.subject_id'),
                json_extract(payload_json, '$.sequence')
            )
            WHERE object_type = 'control_event';
            """
        )

    def append(
        self, record: ContractObject, principal: AuthenticatedPrincipal
    ) -> None:
        require_authenticated(principal)
        if isinstance(record, Decision):
            raise PermissionError("Decisions must be recorded through the Gate ledger")
        self._authorize_record(principal, record, write=True)
        with self.connection:
            self._append_checked(record)

    def read(
        self,
        object_type: ObjectType,
        object_id: str,
        principal: AuthenticatedPrincipal,
        version: int | None = None,
    ) -> ContractObject:
        require_authenticated(principal)
        record = self._get(object_type, object_id, version)
        self._authorize_record(principal, record, write=False)
        return record

    def _get(
        self,
        object_type: ObjectType,
        object_id: str,
        version: int | None = None,
    ) -> ContractObject:
        row = self._select_payload(ObjectType(object_type), object_id, version)
        if row is None:
            raise KeyError("object was not found")
        return contract_from_persisted_dict(json.loads(row[0]))

    def add_cross_space_reference(
        self,
        reference: CrossSpaceReference,
        principal: AuthenticatedPrincipal,
    ) -> None:
        require_authenticated(principal)
        if principal.role is not ActorRole.USER:
            raise PermissionError("only the user may establish a cross-space relationship")
        source = self._get(reference.source_type, reference.source_id)
        target = self._get(reference.target_type, reference.target_id)
        if source.space is target.space:
            raise ValueError("cross-space reference must cross exactly one space boundary")
        event = self._get(ObjectType.CONTROL_EVENT, reference.control_event_id)
        if not isinstance(event, ControlEvent) or (
            event.subject_type,
            event.subject_id,
        ) != (reference.source_type, reference.source_id):
            raise PermissionError("audit event does not match the reference source")
        with self.connection:
            self.connection.execute(
                """
                INSERT INTO cross_space_references
                    (reference_id, source_type, source_id, target_type, target_id, control_event_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    reference.id,
                    reference.source_type.value,
                    reference.source_id,
                    reference.target_type.value,
                    reference.target_id,
                    reference.control_event_id,
                ),
            )

    def record_decision(
        self,
        decision: Decision,
        principal: AuthenticatedPrincipal,
    ) -> None:
        require_authenticated(principal)
        if (
            principal.role is not ActorRole.USER
            or decision.decider_role is not ActorRole.USER
            or decision.decider_id != principal.principal_id
        ):
            raise PermissionError("Gate decision requires the authenticated user")
        target = self._get(decision.target_type, decision.target_id)
        current_hash = record_hash(target)
        if (
            target.version != decision.target_version
            or current_hash != decision.target_hash
        ):
            raise PermissionError("Gate decision is stale or targets different content")
        expected_type = GATE_TARGETS.get(decision.gate)
        if expected_type is not None and expected_type is not decision.target_type:
            raise PermissionError("Gate targets the wrong object type")
        payload = self._serialize(decision)
        try:
            with self.connection:
                self.connection.execute(
                    """
                    INSERT INTO gate_decisions (
                        decision_id, replay_key, gate, target_type, target_id,
                        target_version, target_hash, scope, outcome, decider_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        decision.id, decision.replay_key, decision.gate.value,
                        decision.target_type.value, decision.target_id,
                        decision.target_version, decision.target_hash,
                        decision.scope, decision.outcome.value, decision.decider_id,
                    ),
                )
                self._insert_object(decision, payload)
        except sqlite3.IntegrityError as exc:
            raise PermissionError("Gate decision replay or duplicate authorization") from exc

    def transition_state(
        self,
        object_type: ObjectType,
        object_id: str,
        next_state: str,
        principal: AuthenticatedPrincipal,
        *,
        decision_id: str | None = None,
        scope: str | None = None,
    ) -> ContractObject:
        require_authenticated(principal)
        with self.connection:
            current = self._get(object_type, object_id)
            self._authorize_record(principal, current, write=True)
            validate_transition(current, next_state)
            gate = gate_for_transition(current, next_state)
            if gate is not None:
                if principal.role is not ActorRole.USER or decision_id is None or scope is None:
                    raise PermissionError("gated transition requires the authenticated user")
                expected_outcome = (
                    DecisionOutcome.REJECT
                    if current.object_type is ObjectType.PROPOSAL
                    and ProposalState(next_state) is ProposalState.REJECTED
                    else DecisionOutcome.ACCEPT
                )
                self._consume_decision(
                    decision_id=decision_id,
                    action_id=f"transition-{current.object_type.value}-{current.id}-{current.version + 1}",
                    gate=gate,
                    target=current,
                    scope=scope,
                    outcome=expected_outcome,
                    principal=principal,
                )
            elif decision_id is not None:
                raise PermissionError("a Gate decision cannot authorize an unrelated transition")
            payload = current.to_dict()
            payload["version"] = current.version + 1
            payload["state"] = str(next_state)
            updated = contract_from_persisted_dict(payload)
            self._validate_relationships(updated)
            self._insert_object(updated, self._serialize(updated))
            return updated

    def promote_artifact(
        self,
        candidate: ArtifactCandidate,
        principal: AuthenticatedPrincipal,
        *,
        decision_id: str | None = None,
    ) -> ArtifactReference:
        require_authenticated(principal)
        with self.connection:
            current = self._get(ObjectType.ARTIFACT_REFERENCE, candidate.artifact_id)
            if not isinstance(current, ArtifactReference):
                raise TypeError("artifact record has the wrong type")
            self._authorize_record(principal, current, write=True)
            if (
                current.data_class is not DataClass.LOCAL_ONLY
                or current.sha256 != candidate.artifact_sha256
                or (current.owner_type, current.owner_id)
                != (candidate.owner_type, candidate.owner_id)
            ):
                raise PermissionError("artifact candidate does not match persisted metadata")
            safe_kind = classify_artifact_candidate(candidate, principal)
            confirmation_id = None
            if safe_kind is CloudSafeKind.USER_CONFIRMED_EXPORT:
                if decision_id is None:
                    raise PermissionError("export requires an exact user decision")
                scope = artifact_export_scope(candidate, current.version)
                self._consume_decision(
                    decision_id=decision_id,
                    action_id=f"artifact-export-{current.id}-{current.version + 1}",
                    gate=GateKind.ARTIFACT_EXPORT,
                    target=current,
                    scope=scope,
                    outcome=DecisionOutcome.ACCEPT,
                    principal=principal,
                )
                confirmation_id = identifier(decision_id, "decision_id")
            elif decision_id is not None:
                raise PermissionError("decision cannot be reused for a non-export artifact")
            payload = current.to_dict()
            payload.update(
                {
                    "version": current.version + 1,
                    "data_class": DataClass.CLOUD_SAFE.value,
                    "cloud_safe_kind": safe_kind.value,
                    "storage_ref": candidate.storage_ref,
                    "user_confirmation_decision_id": confirmation_id,
                }
            )
            updated = contract_from_persisted_dict(payload)
            self._validate_relationships(updated)
            self._insert_object(updated, self._serialize(updated))
            return updated

    def _append_checked(self, record: ContractObject) -> None:
        if isinstance(record, ArtifactReference):
            if record.data_class is DataClass.SECRET:
                raise PermissionError("secret never becomes an Artifact")
            if (
                record.data_class is DataClass.CLOUD_SAFE
                or record.storage_ref is not None
                or record.cloud_safe_kind is not None
            ):
                raise PermissionError("cloud-safe classification must come from server policy")
        latest_row = self._select_payload(record.object_type, record.id, None)
        if latest_row is None:
            if record.version != 1:
                raise ValueError("first object version must be one")
        else:
            previous = contract_from_persisted_dict(json.loads(latest_row[0]))
            if record.version != previous.version + 1:
                raise ValueError("object versions must be continuous")
            if record.created_at != previous.created_at:
                raise ValueError("created_at is immutable")
            old_state = getattr(previous, "state", None)
            new_state = getattr(record, "state", None)
            if new_state != old_state:
                validate_transition(previous, str(new_state))
                if gate_for_transition(previous, str(new_state)) is not None:
                    raise PermissionError("gated state must use atomic Gate transition")
        self._validate_relationships(record)
        self._insert_object(record, self._serialize(record))

    def _insert_object(self, record: ContractObject, payload: str) -> None:
        try:
            self.connection.execute(
                """
                INSERT INTO contract_objects
                    (object_type, object_id, version, space, payload_json, record_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    record.object_type.value, record.id, record.version,
                    record.space.value, payload, record_hash(record),
                ),
            )
        except sqlite3.IntegrityError as exc:
            raise ValueError("append-only object constraint failed") from exc

    def _consume_decision(
        self,
        *,
        decision_id: str,
        action_id: str,
        gate: GateKind,
        target: ContractObject,
        scope: str,
        outcome: DecisionOutcome,
        principal: AuthenticatedPrincipal,
    ) -> None:
        decision_id = identifier(decision_id, "decision_id")
        row = self.connection.execute(
            """
            SELECT gate, target_type, target_id, target_version, target_hash,
                   scope, outcome, decider_id
            FROM gate_decisions WHERE decision_id = ?
            """,
            (decision_id,),
        ).fetchone()
        current_hash = record_hash(target)
        expected: tuple[Any, ...] = (
            gate.value, target.object_type.value, target.id, target.version,
            current_hash, scope, outcome.value, principal.principal_id,
        )
        if row is None or tuple(row) != expected:
            raise PermissionError("Gate decision does not authorize this exact action")
        try:
            self.connection.execute(
                """
                INSERT INTO gate_consumptions
                    (decision_id, action_id, consumed_target_hash)
                VALUES (?, ?, ?)
                """,
                (decision_id, action_id, current_hash),
            )
        except sqlite3.IntegrityError as exc:
            raise PermissionError("Gate decision cannot be replayed") from exc

    def _validate_relationships(self, record: ContractObject) -> None:
        relations: list[tuple[ObjectType, str]] = []
        if isinstance(record, WorkItem):
            relations.append((ObjectType.NODE, record.target_node_id))
        elif isinstance(record, AgentRuntime):
            relations.append((ObjectType.NODE, record.node_id))
        elif isinstance(record, Mission):
            relations.append((ObjectType.WORK_ITEM, record.work_item_id))
        elif isinstance(record, Run):
            relations.extend(
                [
                    (ObjectType.MISSION, record.mission_id),
                    (ObjectType.AGENT_RUNTIME, record.agent_runtime_id),
                ]
            )
        elif isinstance(record, Interaction):
            relations.append((ObjectType.RUN, record.run_id))
        elif isinstance(record, ArtifactReference):
            relations.append((record.owner_type, record.owner_id))
        elif isinstance(record, ControlEvent):
            relations.append((record.subject_type, record.subject_id))
        elif isinstance(record, Capability):
            relations.append((ObjectType.PRODUCT_AREA, record.product_area_id))
        elif record.object_type is ObjectType.OBSERVATION:
            relations.append((ObjectType.CAPABILITY, record.capability_id))
        elif isinstance(record, Proposal):
            relations.append((ObjectType.CAPABILITY, record.capability_id))
            relations.extend(
                (ObjectType.OBSERVATION, item)
                for item in record.source_observation_ids
            )
        elif isinstance(record, Release):
            relations.append((ObjectType.PROPOSAL, record.proposal_id))
        for related_type, related_id in relations:
            related = self._get(related_type, related_id)
            if related.object_type is not related_type:
                raise ValueError("relationship type mismatch")
            if related.space is not record.space:
                self._require_cross_space(record, related)
        if isinstance(record, ControlEvent):
            row = self.connection.execute(
                """
                SELECT MAX(CAST(json_extract(payload_json, '$.sequence') AS INTEGER))
                FROM contract_objects
                WHERE object_type = 'control_event'
                  AND json_extract(payload_json, '$.subject_type') = ?
                  AND json_extract(payload_json, '$.subject_id') = ?
                """,
                (record.subject_type.value, record.subject_id),
            ).fetchone()
            expected = 1 if row[0] is None else row[0] + 1
            if record.sequence != expected:
                raise ValueError("ControlEvent sequence must be unique and contiguous")

    def _require_cross_space(
        self, record: ContractObject, related: ContractObject
    ) -> None:
        reference_id = getattr(record, "cross_space_reference_id", None)
        if reference_id is None:
            raise PermissionError("cross-space relationship requires a persisted reference")
        row = self.connection.execute(
            """
            SELECT source_type, source_id, target_type, target_id
            FROM cross_space_references WHERE reference_id = ?
            """,
            (reference_id,),
        ).fetchone()
        if row is None:
            raise PermissionError("cross-space reference does not exist")
        pair = (
            (ObjectType(row[0]), row[1]),
            (ObjectType(row[2]), row[3]),
        )
        related_key = (related.object_type, related.id)
        if related_key not in pair:
            raise PermissionError("cross-space reference does not match the relationship")
        if isinstance(record, ArtifactReference):
            owner_key = (record.owner_type, record.owner_id)
            if owner_key not in pair:
                raise PermissionError("artifact owner does not match the reference")
        if isinstance(record, ControlEvent):
            if (ObjectType(row[2]), row[3]) != (
                record.subject_type, record.subject_id,
            ):
                raise PermissionError("event subject does not match the reference target")

    def _authorize_record(
        self,
        principal: AuthenticatedPrincipal,
        record: ContractObject,
        *,
        write: bool,
    ) -> None:
        if principal.role is ActorRole.AGENT_RUNTIME:
            node_id, runtime_id = self._ownership(record)
        else:
            node_id = runtime_id = None
        authorize_operation(
            principal, record.object_type, write=write,
            target_node_id=node_id, target_runtime_id=runtime_id,
        )

    def _ownership(self, record: ContractObject) -> tuple[str, str]:
        if isinstance(record, Run):
            runtime = self._get(ObjectType.AGENT_RUNTIME, record.agent_runtime_id)
            return runtime.node_id, runtime.id
        if isinstance(record, Interaction):
            return self._ownership(self._get(ObjectType.RUN, record.run_id))
        if isinstance(record, ArtifactReference):
            return self._ownership(self._get(record.owner_type, record.owner_id))
        if isinstance(record, ControlEvent):
            return self._ownership(self._get(record.subject_type, record.subject_id))
        if isinstance(record, WorkItem):
            raise PermissionError("runtime ownership requires a bound Run")
        if isinstance(record, Mission):
            raise PermissionError("runtime ownership requires a bound Run")
        raise PermissionError("object has no runtime ownership")

    def _select_payload(
        self, object_type: ObjectType, object_id: str, version: int | None
    ) -> tuple[str, str] | None:
        object_id = identifier(object_id, "object_id")
        if version is None:
            return self.connection.execute(
                """
                SELECT payload_json, record_hash FROM contract_objects
                WHERE object_type = ? AND object_id = ?
                ORDER BY version DESC LIMIT 1
                """,
                (object_type.value, object_id),
            ).fetchone()
        return self.connection.execute(
            """
            SELECT payload_json, record_hash FROM contract_objects
            WHERE object_type = ? AND object_id = ? AND version = ?
            """,
            (object_type.value, object_id, version),
        ).fetchone()

    @staticmethod
    def _serialize(record: ContractObject) -> str:
        return json.dumps(
            record.to_dict(), ensure_ascii=True, separators=(",", ":"), sort_keys=True
        )


def artifact_export_scope(candidate: ArtifactCandidate, version: int) -> str:
    return (
        f"artifact:{candidate.artifact_id}@{version};"
        f"hash={candidate.artifact_sha256};"
        f"owner={candidate.owner_type.value}/{candidate.owner_id};"
        f"kind={CloudSafeKind.USER_CONFIRMED_EXPORT.value};"
        f"storage={candidate.storage_ref}"
    )
