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
from .validation import identifier, iso_timestamp, positive_version, sha256, summary

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
    GateKind.SENSITIVE_CHANGE: ObjectType.RELEASE,
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
        and ReleaseState(next_state) in {
            ReleaseState.RELEASED, ReleaseState.ROLLED_BACK,
        }
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
    source_version: int
    source_hash: str
    target_version: int
    target_hash: str
    control_event_version: int
    control_event_hash: str
    creator_id: str
    created_at: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "id", identifier(self.id, "id"))
        object.__setattr__(self, "source_type", ObjectType(self.source_type))
        object.__setattr__(self, "source_id", identifier(self.source_id, "source_id"))
        object.__setattr__(self, "target_type", ObjectType(self.target_type))
        object.__setattr__(self, "target_id", identifier(self.target_id, "target_id"))
        object.__setattr__(
            self, "control_event_id", identifier(self.control_event_id, "control_event_id")
        )
        object.__setattr__(
            self, "source_version", positive_version(self.source_version)
        )
        object.__setattr__(self, "source_hash", sha256(self.source_hash))
        object.__setattr__(
            self, "target_version", positive_version(self.target_version)
        )
        object.__setattr__(self, "target_hash", sha256(self.target_hash))
        object.__setattr__(
            self, "control_event_version",
            positive_version(self.control_event_version),
        )
        object.__setattr__(
            self, "control_event_hash", sha256(self.control_event_hash)
        )
        object.__setattr__(
            self, "creator_id", identifier(self.creator_id, "creator_id")
        )
        object.__setattr__(
            self, "created_at", iso_timestamp(self.created_at, "created_at")
        )
        if (self.source_type, self.target_type) not in EXPLICIT_CROSS_SPACE_PAIRS:
            raise PermissionError("cross-space relationship is not allowed")


@dataclass(frozen=True, slots=True)
class ArtifactCandidate:
    artifact_id: str
    artifact_sha256: str
    owner_type: ObjectType
    owner_id: str
    storage_ref: str
    summary_label: str

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "artifact_id", identifier(self.artifact_id, "artifact_id")
        )
        object.__setattr__(self, "artifact_sha256", sha256(self.artifact_sha256))
        object.__setattr__(self, "owner_type", ObjectType(self.owner_type))
        object.__setattr__(self, "owner_id", identifier(self.owner_id, "owner_id"))
        object.__setattr__(
            self, "storage_ref", identifier(self.storage_ref, "storage_ref")
        )
        object.__setattr__(
            self, "summary_label", summary(self.summary_label, "summary_label")
        )


@dataclass(frozen=True, slots=True)
class ArtifactProvenance:
    artifact_id: str
    artifact_sha256: str
    owner_type: ObjectType
    owner_id: str
    source_kind: ArtifactSourceKind
    source_locator: str
    policy_evidence: str

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
            self, "policy_evidence", identifier(self.policy_evidence, "policy_evidence")
        )


class IsolatedArtifactPolicyRegistry:
    """Immutable synthetic server policy registry; never accepts request-time attestations."""

    __slots__ = ("_entries",)

    def __init__(
        self, entries: tuple[ArtifactProvenance, ...], *, isolated: bool
    ) -> None:
        if not isolated:
            raise RuntimeError("synthetic artifact policy registry is test-only")
        mapped: dict[tuple[object, ...], ArtifactProvenance] = {}
        for entry in entries:
            key = (
                entry.artifact_id, entry.artifact_sha256,
                entry.owner_type, entry.owner_id,
            )
            if key in mapped:
                raise ValueError("artifact provenance must be unique")
            mapped[key] = entry
        self._entries = mapped

    def resolve(self, candidate: ArtifactCandidate) -> ArtifactProvenance:
        key = (
            candidate.artifact_id, candidate.artifact_sha256,
            candidate.owner_type, candidate.owner_id,
        )
        try:
            return self._entries[key]
        except KeyError as exc:
            raise PermissionError(
                "artifact lacks trusted server provenance"
            ) from exc


def classify_artifact_candidate(
    candidate: ArtifactCandidate,
    provenance: ArtifactProvenance,
    principal: AuthenticatedPrincipal,
) -> CloudSafeKind:
    """Classify only immutable server provenance; caller labels are non-authoritative."""

    require_authenticated(principal)
    if (
        candidate.artifact_id != provenance.artifact_id
        or candidate.artifact_sha256 != provenance.artifact_sha256
        or candidate.owner_type is not provenance.owner_type
        or candidate.owner_id != provenance.owner_id
    ):
        raise PermissionError("artifact provenance does not match the candidate")
    if ABSOLUTE_PATH.match(provenance.source_locator):
        raise PermissionError("local path content cannot cross the boundary")
    try:
        safe_kind = SAFE_SOURCE_MAP[provenance.source_kind]
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

