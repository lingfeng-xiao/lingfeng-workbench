"""Fourteen versioned control-plane objects for the two product spaces."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, ClassVar, TypeVar

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
from .validation import (
    commit_sha,
    identifier,
    iso_timestamp,
    opaque_workspace_ref,
    positive_version,
    sha256,
    summary,
)


class ContractObject:
    object_type: ClassVar[ObjectType]
    space: ClassVar[Space]

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["object_type"] = self.object_type.value
        payload["space"] = self.space.value
        return payload


def _common(record: Any) -> None:
    object.__setattr__(record, "id", identifier(record.id, "id"))
    object.__setattr__(record, "version", positive_version(record.version))
    object.__setattr__(
        record, "created_at", iso_timestamp(record.created_at, "created_at")
    )


def _ids(values: tuple[str, ...] | list[str], field: str) -> tuple[str, ...]:
    normalized = tuple(identifier(value, field) for value in values)
    if len(normalized) != len(set(normalized)):
        raise ValueError(f"{field} must not contain duplicates")
    return normalized


@dataclass(frozen=True, slots=True)
class WorkItem(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.WORK_ITEM
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    title: str
    objective: str
    target_node_id: str
    local_workspace_ref: str
    state: WorkState = WorkState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "title", summary(self.title, "title"))
        object.__setattr__(self, "objective", summary(self.objective, "objective"))
        object.__setattr__(
            self, "target_node_id", identifier(self.target_node_id, "target_node_id")
        )
        object.__setattr__(
            self,
            "local_workspace_ref",
            opaque_workspace_ref(self.local_workspace_ref, self.target_node_id),
        )
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Mission(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.MISSION
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    work_item_id: str
    objective: str
    state: WorkState = WorkState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self, "work_item_id", identifier(self.work_item_id, "work_item_id")
        )
        object.__setattr__(self, "objective", summary(self.objective, "objective"))
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Run(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.RUN
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    mission_id: str
    agent_runtime_id: str
    state: WorkState = WorkState.READY

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "mission_id", identifier(self.mission_id, "mission_id"))
        object.__setattr__(
            self,
            "agent_runtime_id",
            identifier(self.agent_runtime_id, "agent_runtime_id"),
        )
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Interaction(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.INTERACTION
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    run_id: str
    kind: str
    prompt_summary: str
    state: InteractionState = InteractionState.PENDING

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "run_id", identifier(self.run_id, "run_id"))
        object.__setattr__(self, "kind", identifier(self.kind, "kind"))
        object.__setattr__(
            self,
            "prompt_summary",
            summary(self.prompt_summary, "prompt_summary"),
        )
        object.__setattr__(self, "state", InteractionState(self.state))


@dataclass(frozen=True, slots=True)
class Node(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.NODE
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    display_name: str
    state: WorkState = WorkState.ACTIVE

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self, "display_name", summary(self.display_name, "display_name")
        )
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class AgentRuntime(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.AGENT_RUNTIME
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    node_id: str
    runtime_kind: str
    capabilities: tuple[str, ...] = ()
    state: WorkState = WorkState.ACTIVE

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "node_id", identifier(self.node_id, "node_id"))
        object.__setattr__(
            self, "runtime_kind", identifier(self.runtime_kind, "runtime_kind")
        )
        object.__setattr__(
            self, "capabilities", _ids(self.capabilities, "capability")
        )
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class ArtifactReference(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.ARTIFACT_REFERENCE
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    owner_type: ObjectType
    owner_id: str
    data_class: DataClass
    origin: str
    sha256: str
    size_bytes: int
    storage_ref: str | None = None
    cloud_safe_kind: CloudSafeKind | None = None
    user_confirmation_decision_id: str | None = None

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "owner_type", ObjectType(self.owner_type))
        object.__setattr__(self, "owner_id", identifier(self.owner_id, "owner_id"))
        object.__setattr__(self, "data_class", DataClass(self.data_class))
        object.__setattr__(self, "origin", summary(self.origin, "origin"))
        object.__setattr__(self, "sha256", sha256(self.sha256))
        if (
            not isinstance(self.size_bytes, int)
            or isinstance(self.size_bytes, bool)
            or self.size_bytes < 0
        ):
            raise ValueError("size_bytes must be a non-negative integer")
        if self.storage_ref is not None:
            object.__setattr__(
                self, "storage_ref", identifier(self.storage_ref, "storage_ref")
            )
        if self.cloud_safe_kind is not None:
            object.__setattr__(
                self, "cloud_safe_kind", CloudSafeKind(self.cloud_safe_kind)
            )
        if self.user_confirmation_decision_id is not None:
            object.__setattr__(
                self,
                "user_confirmation_decision_id",
                identifier(
                    self.user_confirmation_decision_id,
                    "user_confirmation_decision_id",
                ),
            )


@dataclass(frozen=True, slots=True)
class ControlEvent(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.CONTROL_EVENT
    space: ClassVar[Space] = Space.MY_WORK

    id: str
    version: int
    created_at: str
    subject_type: ObjectType
    subject_id: str
    sequence: int
    event_type: str
    event_summary: str

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "subject_type", ObjectType(self.subject_type))
        object.__setattr__(
            self, "subject_id", identifier(self.subject_id, "subject_id")
        )
        if (
            not isinstance(self.sequence, int)
            or isinstance(self.sequence, bool)
            or self.sequence < 1
        ):
            raise ValueError("sequence must be a positive integer")
        object.__setattr__(
            self, "event_type", identifier(self.event_type, "event_type")
        )
        object.__setattr__(
            self, "event_summary", summary(self.event_summary, "event_summary")
        )


@dataclass(frozen=True, slots=True)
class ProductArea(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.PRODUCT_AREA
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    title: str
    state: WorkState = WorkState.ACTIVE

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "title", summary(self.title, "title"))
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Capability(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.CAPABILITY
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    product_area_id: str
    title: str
    state: WorkState = WorkState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self,
            "product_area_id",
            identifier(self.product_area_id, "product_area_id"),
        )
        object.__setattr__(self, "title", summary(self.title, "title"))
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Observation(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.OBSERVATION
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    capability_id: str
    observation_summary: str
    state: WorkState = WorkState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self, "capability_id", identifier(self.capability_id, "capability_id")
        )
        object.__setattr__(
            self,
            "observation_summary",
            summary(self.observation_summary, "observation_summary"),
        )
        object.__setattr__(self, "state", WorkState(self.state))


@dataclass(frozen=True, slots=True)
class Proposal(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.PROPOSAL
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    capability_id: str
    source_observation_ids: tuple[str, ...]
    proposal_summary: str
    state: ProposalState = ProposalState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self, "capability_id", identifier(self.capability_id, "capability_id")
        )
        object.__setattr__(
            self,
            "source_observation_ids",
            _ids(self.source_observation_ids, "source_observation_id"),
        )
        object.__setattr__(
            self,
            "proposal_summary",
            summary(self.proposal_summary, "proposal_summary"),
        )
        object.__setattr__(self, "state", ProposalState(self.state))


@dataclass(frozen=True, slots=True)
class Release(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.RELEASE
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    proposal_id: str
    commit_sha: str
    saved_version: str
    state: ReleaseState = ReleaseState.DRAFT

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(
            self, "proposal_id", identifier(self.proposal_id, "proposal_id")
        )
        object.__setattr__(self, "commit_sha", commit_sha(self.commit_sha))
        object.__setattr__(
            self, "saved_version", identifier(self.saved_version, "saved_version")
        )
        object.__setattr__(self, "state", ReleaseState(self.state))


@dataclass(frozen=True, slots=True)
class Decision(ContractObject):
    object_type: ClassVar[ObjectType] = ObjectType.DECISION
    space: ClassVar[Space] = Space.WORKBENCH

    id: str
    version: int
    created_at: str
    gate: GateKind
    target_type: ObjectType
    target_id: str
    target_version: int
    scope: str
    decider_id: str
    decider_role: ActorRole
    outcome: DecisionOutcome
    replay_key: str

    def __post_init__(self) -> None:
        _common(self)
        object.__setattr__(self, "gate", GateKind(self.gate))
        object.__setattr__(self, "target_type", ObjectType(self.target_type))
        object.__setattr__(
            self, "target_id", identifier(self.target_id, "target_id")
        )
        object.__setattr__(
            self, "target_version", positive_version(self.target_version)
        )
        object.__setattr__(self, "scope", summary(self.scope, "scope"))
        object.__setattr__(
            self, "decider_id", identifier(self.decider_id, "decider_id")
        )
        object.__setattr__(self, "decider_role", ActorRole(self.decider_role))
        object.__setattr__(self, "outcome", DecisionOutcome(self.outcome))
        object.__setattr__(
            self, "replay_key", identifier(self.replay_key, "replay_key")
        )


OBJECT_CLASSES = {
    cls.object_type: cls
    for cls in (
        WorkItem,
        Mission,
        Run,
        Interaction,
        Node,
        AgentRuntime,
        ArtifactReference,
        ControlEvent,
        ProductArea,
        Capability,
        Observation,
        Proposal,
        Release,
        Decision,
    )
}
T = TypeVar("T", bound=ContractObject)


def contract_from_dict(payload: dict[str, Any]) -> ContractObject:
    values = dict(payload)
    object_type = ObjectType(values.pop("object_type"))
    declared_space = Space(values.pop("space"))
    cls = OBJECT_CLASSES[object_type]
    if declared_space is not cls.space:
        raise ValueError("object space does not match its contract")
    return cls(**values)
