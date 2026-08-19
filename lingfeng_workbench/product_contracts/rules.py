"""State, Gate, data-boundary, and cross-space business rules."""

from __future__ import annotations

from dataclasses import dataclass

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
    Space,
    WorkState,
)
from .models import ArtifactReference, ContractObject, Decision
from .validation import identifier

WORK_TRANSITIONS = {
    WorkState.DRAFT: {WorkState.READY, WorkState.CANCELLED},
    WorkState.READY: {WorkState.ACTIVE, WorkState.CANCELLED},
    WorkState.ACTIVE: {
        WorkState.WAITING,
        WorkState.COMPLETED,
        WorkState.FAILED,
        WorkState.UNCERTAIN,
        WorkState.CANCELLED,
    },
    WorkState.WAITING: {
        WorkState.ACTIVE,
        WorkState.FAILED,
        WorkState.UNCERTAIN,
        WorkState.CANCELLED,
    },
}
INTERACTION_TRANSITIONS = {
    InteractionState.PENDING: {
        InteractionState.RESOLVED,
        InteractionState.EXPIRED,
        InteractionState.CANCELLED,
    }
}
PROPOSAL_TRANSITIONS = {
    ProposalState.DRAFT: {ProposalState.PROPOSED, ProposalState.WITHDRAWN},
    ProposalState.PROPOSED: {
        ProposalState.ACCEPTED,
        ProposalState.REJECTED,
        ProposalState.WITHDRAWN,
    },
}
RELEASE_TRANSITIONS = {
    ReleaseState.DRAFT: {ReleaseState.READY},
    ReleaseState.READY: {ReleaseState.RELEASED, ReleaseState.FAILED},
    ReleaseState.FAILED: {ReleaseState.READY},
    ReleaseState.RELEASED: {ReleaseState.ROLLED_BACK},
}
GATE_TARGETS = {
    GateKind.PROPOSAL: ObjectType.PROPOSAL,
    GateKind.RELEASE: ObjectType.RELEASE,
}
CLOUD_SAFE_ALLOWLIST = frozenset(CloudSafeKind)


def require_gate_decision(
    decision: Decision | None,
    *,
    gate: GateKind,
    target_type: ObjectType,
    target_id: str,
    target_version: int,
    scope: str | None = None,
) -> None:
    if decision is None:
        raise PermissionError(f"{gate.value} requires an explicit user decision")
    if decision.decider_role is not ActorRole.USER:
        raise PermissionError("Workbench and machine actors cannot authorize a Gate")
    if decision.outcome is not DecisionOutcome.ACCEPT:
        raise PermissionError("the Gate decision is not an acceptance")
    expected = (
        gate,
        target_type,
        target_id,
        target_version,
    )
    actual = (
        decision.gate,
        decision.target_type,
        decision.target_id,
        decision.target_version,
    )
    if actual != expected:
        raise PermissionError("the Gate decision targets another object or version")
    if scope is not None and decision.scope != scope:
        raise PermissionError("the Gate decision targets another scope")


def validate_transition(
    record: ContractObject,
    next_state: str,
    decision: Decision | None = None,
) -> None:
    current = getattr(record, "state", None)
    if record.object_type is ObjectType.INTERACTION:
        target = InteractionState(next_state)
        allowed = INTERACTION_TRANSITIONS.get(current, set())
    elif record.object_type is ObjectType.PROPOSAL:
        target = ProposalState(next_state)
        allowed = PROPOSAL_TRANSITIONS.get(current, set())
        if target in {ProposalState.ACCEPTED, ProposalState.REJECTED}:
            if decision is None or decision.outcome.value != target.value.removesuffix("ed"):
                raise PermissionError("Proposal resolution requires the matching G1 decision")
            if target is ProposalState.ACCEPTED:
                require_gate_decision(
                    decision,
                    gate=GateKind.PROPOSAL,
                    target_type=record.object_type,
                    target_id=record.id,
                    target_version=record.version,
                )
            elif (
                decision.decider_role is not ActorRole.USER
                or decision.gate is not GateKind.PROPOSAL
                or decision.target_type is not record.object_type
                or decision.target_id != record.id
                or decision.target_version != record.version
            ):
                raise PermissionError("Proposal rejection requires the matching user G1 decision")
    elif record.object_type is ObjectType.RELEASE:
        target = ReleaseState(next_state)
        allowed = RELEASE_TRANSITIONS.get(current, set())
        if target is ReleaseState.RELEASED:
            require_gate_decision(
                decision,
                gate=GateKind.RELEASE,
                target_type=record.object_type,
                target_id=record.id,
                target_version=record.version,
            )
    else:
        target = WorkState(next_state)
        allowed = WORK_TRANSITIONS.get(current, set())
    if target not in allowed:
        raise ValueError(
            f"illegal {record.object_type.value} transition: {current} -> {target}"
        )


def classification_or_default(value: DataClass | str | None) -> DataClass:
    if value is None:
        return DataClass.LOCAL_ONLY
    return DataClass(value)


def validate_artifact_upload(reference: ArtifactReference) -> None:
    if reference.data_class is DataClass.SECRET:
        raise PermissionError("secret never becomes Artifact content")
    if reference.data_class is not DataClass.CLOUD_SAFE:
        raise PermissionError("only declared cloud-safe content may be uploaded")
    if reference.cloud_safe_kind not in CLOUD_SAFE_ALLOWLIST:
        raise PermissionError("content kind is outside the closed cloud-safe allowlist")
    if reference.storage_ref is None:
        raise ValueError("cloud-safe content requires an immutable storage reference")
    if (
        reference.cloud_safe_kind is CloudSafeKind.USER_CONFIRMED_EXPORT
        and reference.user_confirmation_decision_id is None
    ):
        raise PermissionError("an export requires an exact user safety confirmation")


@dataclass(frozen=True, slots=True)
class CrossSpaceReference:
    source_type: ObjectType
    source_id: str
    target_type: ObjectType
    target_id: str
    control_event_id: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "source_type", ObjectType(self.source_type))
        object.__setattr__(self, "source_id", identifier(self.source_id, "source_id"))
        object.__setattr__(self, "target_type", ObjectType(self.target_type))
        object.__setattr__(self, "target_id", identifier(self.target_id, "target_id"))
        object.__setattr__(
            self,
            "control_event_id",
            identifier(self.control_event_id, "control_event_id"),
        )


EXPLICIT_CROSS_SPACE_PAIRS = frozenset(
    {
        (ObjectType.WORK_ITEM, ObjectType.PROPOSAL),
        (ObjectType.PROPOSAL, ObjectType.WORK_ITEM),
    }
)


def validate_reference(
    source: ContractObject,
    target: ContractObject,
    explicit: CrossSpaceReference | None = None,
) -> None:
    if source.space is target.space:
        return
    if explicit is None:
        raise PermissionError("cross-space references must be explicit and auditable")
    expected = (
        source.object_type,
        source.id,
        target.object_type,
        target.id,
    )
    actual = (
        explicit.source_type,
        explicit.source_id,
        explicit.target_type,
        explicit.target_id,
    )
    if actual != expected:
        raise PermissionError("cross-space reference does not match the objects")
    if (source.object_type, target.object_type) not in EXPLICIT_CROSS_SPACE_PAIRS:
        raise PermissionError("this cross-space relationship is not allowed")
