"""Isolated in-memory SQLite contract verifier; never a cloud fact-source adapter."""

from __future__ import annotations

import json
import sqlite3
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from .api import authorize_operation
from .auth import AuthenticatedPrincipal, require_authenticated
from .enums import (
    ActorRole,
    CloudSafeKind,
    DataClass,
    DecisionOutcome,
    GateKind,
    InteractionState,
    ObjectType,
    ProposalState,
    ReleaseState,
    WorkState,
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
    _PERSISTED,
    _contract_from_store,
    record_hash,
)
from .rules import (
    ArtifactCandidate,
    ArtifactProvenance,
    CrossSpaceReference,
    GATE_TARGETS,
    classify_artifact_candidate,
    gate_for_transition,
    validate_transition,
)
from .validation import identifier


IMMUTABLE_RELATION_FIELDS = {
    ObjectType.WORK_ITEM: ("target_node_id", "local_workspace_ref"),
    ObjectType.MISSION: ("work_item_id",),
    ObjectType.RUN: ("mission_id", "agent_runtime_id"),
    ObjectType.INTERACTION: ("run_id",),
    ObjectType.AGENT_RUNTIME: ("node_id",),
    ObjectType.ARTIFACT_REFERENCE: (
        "owner_type", "owner_id", "cross_space_reference_id",
        "cross_space_work_item_id",
    ),
    ObjectType.CONTROL_EVENT: (
        "subject_type", "subject_id", "sequence", "cross_space_reference_id",
    ),
    ObjectType.CAPABILITY: ("product_area_id",),
    ObjectType.OBSERVATION: ("capability_id",),
    ObjectType.PROPOSAL: ("capability_id", "source_observation_ids"),
    ObjectType.RELEASE: ("proposal_id", "commit_sha", "saved_version"),
}

INITIAL_STATES = {
    ObjectType.WORK_ITEM: WorkState.DRAFT,
    ObjectType.MISSION: WorkState.DRAFT,
    ObjectType.RUN: WorkState.READY,
    ObjectType.INTERACTION: InteractionState.PENDING,
    ObjectType.NODE: WorkState.ACTIVE,
    ObjectType.AGENT_RUNTIME: WorkState.ACTIVE,
    ObjectType.PRODUCT_AREA: WorkState.ACTIVE,
    ObjectType.CAPABILITY: WorkState.DRAFT,
    ObjectType.OBSERVATION: WorkState.DRAFT,
    ObjectType.PROPOSAL: ProposalState.DRAFT,
    ObjectType.RELEASE: ReleaseState.DRAFT,
}


class IsolatedSqliteContractStore:
    """Temporary test store. D1 remains the cloud control-plane fact source."""

    def __init__(
        self,
        connection: sqlite3.Connection,
        *,
        isolated: bool,
        artifact_policy_registry: Any | None = None,
        server_clock: Callable[[], datetime] | None = None,
        decision_ttl_seconds: int = 300,
    ) -> None:
        if not isolated:
            raise RuntimeError("SQLite contract store is restricted to isolated tests")
        database_path = connection.execute("PRAGMA database_list").fetchone()[2]
        if database_path:
            raise RuntimeError("SQLite contract store must use an in-memory database")
        self.connection = connection
        self.artifact_policy_registry = artifact_policy_registry
        self.server_clock = server_clock or (lambda: datetime.now(timezone.utc))
        if (
            not isinstance(decision_ttl_seconds, int)
            or isinstance(decision_ttl_seconds, bool)
            or decision_ttl_seconds < 1
            or decision_ttl_seconds > 3600
        ):
            raise ValueError("decision TTL must be between 1 and 3600 seconds")
        self.decision_ttl_seconds = decision_ttl_seconds
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
                control_event_id TEXT NOT NULL,
                source_version INTEGER NOT NULL,
                source_hash TEXT NOT NULL,
                target_version INTEGER NOT NULL,
                target_hash TEXT NOT NULL,
                control_event_version INTEGER NOT NULL,
                control_event_hash TEXT NOT NULL,
                creator_id TEXT NOT NULL,
                created_at TEXT NOT NULL
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
                issued_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
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
        authorization_record = record
        if principal.role is ActorRole.AGENT_RUNTIME:
            latest = self._select_payload(record.object_type, record.id, None)
            if latest is not None:
                authorization_record = self._decode_row(latest)
        self._authorize_record(principal, authorization_record, write=True)
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
        return self._decode_row(row)

    def add_cross_space_reference(
        self,
        reference: CrossSpaceReference,
        principal: AuthenticatedPrincipal,
    ) -> CrossSpaceReference:
        require_authenticated(principal)
        if principal.role is not ActorRole.USER:
            raise PermissionError("only the user may establish a cross-space relationship")
        if reference.creator_id != principal.principal_id:
            raise PermissionError("cross-space creator must be the authenticated user")
        source = self._get(reference.source_type, reference.source_id)
        target = self._get(reference.target_type, reference.target_id)
        event = self._get(
            ObjectType.CONTROL_EVENT,
            reference.control_event_id,
            reference.control_event_version,
        )
        if source.space is target.space:
            raise ValueError("cross-space reference must cross exactly one space boundary")
        if (
            source.version != reference.source_version
            or record_hash(source) != reference.source_hash
            or target.version != reference.target_version
            or record_hash(target) != reference.target_hash
            or record_hash(event) != reference.control_event_hash
        ):
            raise PermissionError("cross-space reference does not bind exact persisted endpoints")
        if not isinstance(event, ControlEvent) or (
            event.subject_type,
            event.subject_id,
        ) != (reference.source_type, reference.source_id):
            raise PermissionError("audit event does not match the reference source")
        persisted = replace(reference, created_at=self._server_timestamp())
        with self.connection:
            self.connection.execute(
                """
                INSERT INTO cross_space_references (
                    reference_id, source_type, source_id, target_type, target_id,
                    control_event_id, source_version, source_hash,
                    target_version, target_hash, control_event_version,
                    control_event_hash, creator_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    persisted.id, persisted.source_type.value, persisted.source_id,
                    persisted.target_type.value, persisted.target_id,
                    persisted.control_event_id, persisted.source_version,
                    persisted.source_hash, persisted.target_version,
                    persisted.target_hash, persisted.control_event_version,
                    persisted.control_event_hash, persisted.creator_id,
                    persisted.created_at,
                ),
            )
        return persisted

    def record_decision(
        self,
        decision: Decision,
        principal: AuthenticatedPrincipal,
    ) -> Decision:
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
        if expected_type is None or expected_type is not decision.target_type:
            raise PermissionError("Gate has no exact target contract")
        latest_decision = self._select_payload(
            ObjectType.DECISION, decision.id, None
        )
        if latest_decision is None:
            if decision.version != 1:
                raise ValueError("first object version must be one")
        else:
            previous_decision = self._decode_row(latest_decision)
            if decision.version != previous_decision.version + 1:
                raise ValueError("object versions must be continuous")
        issued = self._server_now()
        expires = issued + timedelta(seconds=self.decision_ttl_seconds)
        persisted = replace(
            decision,
            created_at=self._format_timestamp(issued),
            expires_at=self._format_timestamp(expires),
        )
        payload = self._serialize(persisted)
        try:
            with self.connection:
                self.connection.execute(
                    """
                    INSERT INTO gate_decisions (
                        decision_id, replay_key, gate, target_type, target_id,
                        target_version, target_hash, scope, outcome, decider_id,
                        issued_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        persisted.id, persisted.replay_key, persisted.gate.value,
                        persisted.target_type.value, persisted.target_id,
                        persisted.target_version, persisted.target_hash,
                        persisted.scope, persisted.outcome.value,
                        persisted.decider_id, persisted.created_at,
                        persisted.expires_at,
                    ),
                )
                self._insert_object(persisted, payload)
        except sqlite3.IntegrityError as exc:
            raise PermissionError("Gate decision replay or duplicate authorization") from exc
        return persisted

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
            updated = self._reconstruct(payload)
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
            if self.artifact_policy_registry is None:
                raise PermissionError("trusted artifact policy registry is required")
            provenance = self.artifact_policy_registry.resolve(candidate)
            safe_kind = classify_artifact_candidate(candidate, provenance, principal)
            confirmation_id = None
            if safe_kind is CloudSafeKind.USER_CONFIRMED_EXPORT:
                if decision_id is None:
                    raise PermissionError("export requires an exact user decision")
                scope = artifact_export_scope(candidate, provenance, current.version)
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
                    "source_kind": provenance.source_kind.value,
                    "source_locator": provenance.source_locator,
                    "policy_evidence": provenance.policy_evidence,
                }
            )
            updated = self._reconstruct(payload)
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
                or record.source_kind is not None
                or record.source_locator is not None
                or record.policy_evidence is not None
            ):
                raise PermissionError("cloud-safe classification must come from server policy")
        latest_row = self._select_payload(record.object_type, record.id, None)
        if latest_row is None:
            if record.version != 1:
                raise ValueError("first object version must be one")
            expected_initial = INITIAL_STATES.get(record.object_type)
            if expected_initial is not None and getattr(record, "state", None) is not expected_initial:
                raise PermissionError("first object version must use the allowed initial state")
        else:
            previous = self._decode_row(latest_row)
            if record.version != previous.version + 1:
                raise ValueError("object versions must be continuous")
            if record.created_at != previous.created_at:
                raise ValueError("created_at is immutable")
            self._validate_immutable_relationships(previous, record)
            old_state = getattr(previous, "state", None)
            new_state = getattr(record, "state", None)
            if new_state != old_state:
                validate_transition(previous, str(new_state))
                if gate_for_transition(previous, str(new_state)) is not None:
                    raise PermissionError("gated state must use atomic Gate transition")
        self._validate_relationships(record)
        self._insert_object(record, self._serialize(record))

    @staticmethod
    def _validate_immutable_relationships(
        previous: ContractObject, record: ContractObject
    ) -> None:
        if previous.object_type is not record.object_type:
            raise PermissionError("object type is immutable")
        for field in IMMUTABLE_RELATION_FIELDS.get(record.object_type, ()):
            if getattr(previous, field) != getattr(record, field):
                raise PermissionError(f"{field} ownership or relationship is immutable")

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

    def consume_sensitive_change(
        self,
        release_id: str,
        decision_id: str,
        scope: str,
        principal: AuthenticatedPrincipal,
    ) -> None:
        require_authenticated(principal)
        if principal.role is not ActorRole.USER:
            raise PermissionError("G3 requires the authenticated user")
        with self.connection:
            release = self._get(ObjectType.RELEASE, release_id)
            self._consume_decision(
                decision_id=decision_id,
                action_id=f"sensitive-change-{release.id}-{release.version}",
                gate=GateKind.SENSITIVE_CHANGE,
                target=release,
                scope=scope,
                outcome=DecisionOutcome.ACCEPT,
                principal=principal,
            )

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
                   scope, outcome, decider_id, issued_at, expires_at
            FROM gate_decisions WHERE decision_id = ?
            """,
            (decision_id,),
        ).fetchone()
        current_hash = record_hash(target)
        expected: tuple[Any, ...] = (
            gate.value, target.object_type.value, target.id, target.version,
            current_hash, scope, outcome.value, principal.principal_id,
        )
        if row is None or tuple(row[:8]) != expected:
            raise PermissionError("Gate decision does not authorize this exact action")
        issued = self._parse_timestamp(row[8])
        expires = self._parse_timestamp(row[9])
        now = self._server_now()
        if (
            issued > now
            or expires <= issued
            or expires - issued > timedelta(seconds=self.decision_ttl_seconds)
            or now >= expires
        ):
            raise PermissionError("Gate decision is future-dated, expired, or over-age")
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
        cross_space_reference_used = False
        if isinstance(record, WorkItem):
            relations.append((ObjectType.NODE, record.target_node_id))
        elif isinstance(record, AgentRuntime):
            relations.append((ObjectType.NODE, record.node_id))
        elif isinstance(record, Mission):
            relations.append((ObjectType.WORK_ITEM, record.work_item_id))
        elif isinstance(record, Run):
            mission = self._get(ObjectType.MISSION, record.mission_id)
            work_item = self._get(ObjectType.WORK_ITEM, mission.work_item_id)
            runtime = self._get(ObjectType.AGENT_RUNTIME, record.agent_runtime_id)
            if work_item.target_node_id != runtime.node_id:
                raise PermissionError(
                    "Run runtime node must match the Mission WorkItem target node"
                )
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
                cross_space_reference_used = True
        reference_id = getattr(record, "cross_space_reference_id", None)
        if reference_id is not None and not cross_space_reference_used:
            raise PermissionError(
                "cross-space reference cannot be attached to a same-space relationship"
            )
        if (
            isinstance(record, ArtifactReference)
            and record.cross_space_work_item_id is not None
            and not cross_space_reference_used
        ):
            raise PermissionError("cross-space WorkItem requires a cross-space owner")
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
            SELECT source_type, source_id, source_version, source_hash,
                   target_type, target_id, target_version, target_hash,
                   control_event_id, control_event_version, control_event_hash,
                   creator_id, created_at
            FROM cross_space_references WHERE reference_id = ?
            """,
            (reference_id,),
        ).fetchone()
        if row is None:
            raise PermissionError("cross-space reference does not exist")
        pair = {
            (ObjectType(row[0]), row[1], row[2], row[3]),
            (ObjectType(row[4]), row[5], row[6], row[7]),
        }
        related_key = (
            related.object_type, related.id, related.version, record_hash(related),
        )
        if related_key not in pair:
            raise PermissionError("cross-space reference does not match the exact relationship")
        audit_event = self._get(
            ObjectType.CONTROL_EVENT, row[8], row[9]
        )
        if record_hash(audit_event) != row[10]:
            raise PermissionError("cross-space audit event hash does not match")
        if isinstance(record, ArtifactReference):
            if record.cross_space_work_item_id is None:
                raise PermissionError(
                    "cross-space Artifact requires its actual WorkItem endpoint"
                )
            work_item = self._get(
                ObjectType.WORK_ITEM, record.cross_space_work_item_id
            )
            owner = self._get(record.owner_type, record.owner_id)
            expected_pair = {
                (owner.object_type, owner.id, owner.version, record_hash(owner)),
                (
                    work_item.object_type, work_item.id,
                    work_item.version, record_hash(work_item),
                ),
            }
            if pair != expected_pair:
                raise PermissionError(
                    "artifact cross-space reference binds unrelated endpoints"
                )
        if isinstance(record, ControlEvent):
            target_key = (
                record.subject_type,
                record.subject_id,
                related.version,
                record_hash(related),
            )
            if target_key not in pair:
                raise PermissionError("event subject does not match the exact reference target")

    def _authorize_record(
        self,
        principal: AuthenticatedPrincipal,
        record: ContractObject,
        *,
        write: bool,
    ) -> None:
        if principal.role is ActorRole.AGENT_RUNTIME:
            node_id, runtime_id = self._ownership(record, principal)
        else:
            node_id = runtime_id = None
        authorize_operation(
            principal, record.object_type, write=write,
            target_node_id=node_id, target_runtime_id=runtime_id,
        )

    def _ownership(
        self,
        record: ContractObject,
        principal: AuthenticatedPrincipal,
    ) -> tuple[str, str]:
        if isinstance(record, Run):
            runtime = self._get(ObjectType.AGENT_RUNTIME, record.agent_runtime_id)
            return runtime.node_id, runtime.id
        if isinstance(record, Interaction):
            return self._ownership(
                self._get(ObjectType.RUN, record.run_id), principal
            )
        if isinstance(record, ArtifactReference):
            return self._ownership(
                self._get(record.owner_type, record.owner_id), principal
            )
        if isinstance(record, ControlEvent):
            return self._ownership(
                self._get(record.subject_type, record.subject_id), principal
            )
        if isinstance(record, (WorkItem, Mission)):
            return self._runtime_chain_ownership(record, principal)
        raise PermissionError("object has no runtime ownership")

    def _runtime_chain_ownership(
        self,
        record: WorkItem | Mission,
        principal: AuthenticatedPrincipal,
    ) -> tuple[str, str]:
        runtime = self._get(ObjectType.AGENT_RUNTIME, principal.runtime_id)
        if runtime.node_id != principal.node_id:
            raise PermissionError("runtime identity does not match persisted Node")
        rows = self.connection.execute(
            """
            SELECT payload_json, record_hash
            FROM contract_objects
            WHERE object_type = ?
              AND json_extract(payload_json, '$.agent_runtime_id') = ?
            ORDER BY object_id, version DESC
            """,
            (ObjectType.RUN.value, runtime.id),
        ).fetchall()
        for row in rows:
            run = self._decode_row(row)
            mission = self._get(ObjectType.MISSION, run.mission_id)
            work_item = self._get(ObjectType.WORK_ITEM, mission.work_item_id)
            if work_item.target_node_id != runtime.node_id:
                continue
            if isinstance(record, Mission) and mission.id == record.id:
                return runtime.node_id, runtime.id
            if isinstance(record, WorkItem) and work_item.id == record.id:
                return runtime.node_id, runtime.id
        raise PermissionError("runtime ownership requires a persisted bound Run")

    def _server_now(self) -> datetime:
        value = self.server_clock()
        if not isinstance(value, datetime) or value.tzinfo is None:
            raise RuntimeError("server clock must return a timezone-aware datetime")
        return value.astimezone(timezone.utc)

    @staticmethod
    def _format_timestamp(value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace(
            "+00:00", "Z"
        )

    @staticmethod
    def _parse_timestamp(value: str) -> datetime:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
            timezone.utc
        )

    def _server_timestamp(self) -> str:
        return self._format_timestamp(self._server_now())

    @staticmethod
    def _reconstruct(payload: dict[str, Any]) -> ContractObject:
        return _contract_from_store(payload, _store_capability=_PERSISTED)

    def _decode_row(self, row: tuple[str, str]) -> ContractObject:
        record = self._reconstruct(json.loads(row[0]))
        if record_hash(record) != row[1]:
            raise RuntimeError("persisted record hash mismatch")
        return record

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


def artifact_export_scope(
    candidate: ArtifactCandidate,
    provenance: ArtifactProvenance,
    version: int,
) -> str:
    return (
        f"artifact:{candidate.artifact_id}@{version};"
        f"hash={candidate.artifact_sha256};"
        f"owner={candidate.owner_type.value}/{candidate.owner_id};"
        f"kind={CloudSafeKind.USER_CONFIRMED_EXPORT.value};"
        f"source={provenance.source_kind.value}/{provenance.source_locator};"
        f"policy={provenance.policy_evidence};"
        f"storage={candidate.storage_ref}"
    )
