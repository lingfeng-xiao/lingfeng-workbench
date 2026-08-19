"""State, Gate, artifact, and explicit cross-space rules."""

from __future__ import annotations

import re
from dataclasses import dataclass

from .auth import AuthenticatedPrincipal, require_authenticated
from .enums import (
    ActorRole,
    ArtifactSourceKind,
    CloudSafeKind,
    GateKind,
    InteractionState,
    ObjectType,
    ProposalState,
    ReleaseState,
    WorkState,
)
from .models import ContractObject
from .validation import identifier, sha256, summary

WORK_TRANSITIONS = {
    WorkState.DRAFT: {WorkState.READY, WorkState.CANCELLED},
    WorkState.READY: {WorkState.ACTIVE, WorkState.CANCELLED},
    WorkState.ACTIVE: {
        WorkState.WAITING, WorkState.COMPLETED, WorkState.FAILED,
        WorkState.UNCERTAIN, WorkState.CANCELLED,
    },
    WorkState.WAITING: {
        WorkState.ACTIVE, WorkState.FAILED, WorkState.UNCERTAIN,
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
        ProposalState.ACCEPTED, ProposalState.REJECTED, ProposalState.WITHDRAWN,
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
    GateKind.ARTIFACT_EXPORT: ObjectType.ARTIFACT_REFERENCE,
}
EXPLICIT_CROSS_SPACE_PAIRS = frozenset(
    {
        (ObjectType.WORK_ITEM, ObjectType.PROPOSAL),
        (ObjectType.PROPOSAL, ObjectType.WORK_ITEM),
    }
)
SAFE_SOURCE_MAP = {
    ArtifactSourceKind.WORKBENCH_DESIGN: CloudSafeKind.WORKBENCH_DESIGN,
    ArtifactSourceKind.WORKBENCH_TEST_REPORT: CloudSafeKind.WORKBENCH_TEST_REPORT,
    ArtifactSourceKind.WORKBENCH_SCREENSHOT: CloudSafeKind.WORKBENCH_SCREENSHOT,
    ArtifactSourceKind.SYNTHETIC_FIXTURE: CloudSafeKind.SYNTHETIC_FIXTURE,
    ArtifactSourceKind.USER_EXPORT: CloudSafeKind.USER_CONFIRMED_EXPORT,
}
ABSOLUTE_PATH = re.compile(r"^(?:[A-Za-z]:[\\/]|/|\\\\)")


def validate_transition(record: ContractObject, next_state: str) -> None:
    current = getattr(record, "state", None)
    if record.object_type is ObjectType.INTERACTION:
        target = InteractionState(next_state)
        allowed = INTERACTION_TRANSITIONS.get(current, set())
    elif record.object_type is ObjectType.PROPOSAL:
        target = ProposalState(next_state)
        allowed = PROPOSAL_TRANSITIONS.get(current, set())
    elif record.object_type is ObjectType.RELEASE:
        target = ReleaseState(next_state)
        allowed = RELEASE_TRANSITIONS.get(current, set())
    else:
        target = WorkState(next_state)
        allowed = WORK_TRANSITIONS.get(current, set())
    if target not in allowed:
        raise ValueError("illegal state transition")


def gate_for_transition(record: ContractObject, next_state: str) -> GateKind | None:
    if (
        record.object_type is ObjectType.PROPOSAL
        and ProposalState(next_state) in {ProposalState.ACCEPTED, ProposalState.REJECTED}
    ):
        return GateKind.PROPOSAL
    if (
        record.object_type is ObjectType.RELEASE
        and ReleaseState(next_state) is ReleaseState.RELEASED
    ):
        return GateKind.RELEASE
    return None


@dataclass(frozen=True, slots=True)
class CrossSpaceReference:
    id: str
    source_type: ObjectType
    source_id: str
    target_type: ObjectType
    target_id: str
    control_event_id: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "id", identifier(self.id, "id"))
        object.__setattr__(self, "source_type", ObjectType(self.source_type))
        object.__setattr__(self, "source_id", identifier(self.source_id, "source_id"))
        object.__setattr__(self, "target_type", ObjectType(self.target_type))
        object.__setattr__(self, "target_id", identifier(self.target_id, "target_id"))
        object.__setattr__(
            self, "control_event_id", identifier(self.control_event_id, "control_event_id")
        )
        if (self.source_type, self.target_type) not in EXPLICIT_CROSS_SPACE_PAIRS:
            raise PermissionError("cross-space relationship is not allowed")


@dataclass(frozen=True, slots=True)
class ArtifactCandidate:
    artifact_id: str
    artifact_sha256: str
    owner_type: ObjectType
    owner_id: str
    source_kind: ArtifactSourceKind
    source_locator: str
    storage_ref: str
    summary_label: str

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "artifact_id", identifier(self.artifact_id, "artifact_id")
        )
        object.__setattr__(self, "artifact_sha256", sha256(self.artifact_sha256))
        object.__setattr__(self, "owner_type", ObjectType(self.owner_type))
        object.__setattr__(self, "owner_id", identifier(self.owner_id, "owner_id"))
        object.__setattr__(self, "source_kind", ArtifactSourceKind(self.source_kind))
        object.__setattr__(
            self, "source_locator", summary(self.source_locator, "source_locator")
        )
        object.__setattr__(
            self, "storage_ref", identifier(self.storage_ref, "storage_ref")
        )
        object.__setattr__(
            self, "summary_label", summary(self.summary_label, "summary_label")
        )


def classify_artifact_candidate(
    candidate: ArtifactCandidate,
    principal: AuthenticatedPrincipal,
) -> CloudSafeKind:
    """Server-owned classification; caller labels never change the decision."""

    require_authenticated(principal)
    if ABSOLUTE_PATH.match(candidate.source_locator):
        raise PermissionError("local path content cannot cross the boundary")
    try:
        safe_kind = SAFE_SOURCE_MAP[candidate.source_kind]
    except KeyError as exc:
        raise PermissionError("artifact source is not cloud-safe") from exc
    if safe_kind in {
        CloudSafeKind.WORKBENCH_DESIGN,
        CloudSafeKind.WORKBENCH_TEST_REPORT,
        CloudSafeKind.WORKBENCH_SCREENSHOT,
    } and principal.role not in {ActorRole.USER, ActorRole.WORKBENCH}:
        raise PermissionError("principal cannot attest a Workbench-owned artifact")
    if safe_kind is CloudSafeKind.USER_CONFIRMED_EXPORT and principal.role is not ActorRole.USER:
        raise PermissionError("only the user can request an export")
    return safe_kind
