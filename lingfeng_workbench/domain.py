"""Stable domain vocabulary shared by the Hermes plugin and office node."""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any


IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
MAX_SUMMARY_CHARS = 800


class WorkItemKind(StrEnum):
    DEMAND = "demand"
    BUG = "bug"
    TASK = "task"
    INVESTIGATION = "investigation"


class BindingKind(StrEnum):
    WORK_ITEM = "work_item"
    MISSION = "mission"


class RunState(StrEnum):
    ASSIGNED = "assigned"
    STARTING = "starting"
    RUNNING = "running"
    WAITING_INTERACTION = "waiting_interaction"
    WAITING_APPROVAL = "waiting_approval"
    COMPLETED = "completed"
    FAILED = "failed"
    INTERRUPTED = "interrupted"
    UNCERTAIN = "uncertain"

    @property
    def is_terminal(self) -> bool:
        return self in {
            RunState.COMPLETED,
            RunState.FAILED,
            RunState.INTERRUPTED,
            RunState.UNCERTAIN,
        }


class InteractionKind(StrEnum):
    CLARIFICATION = "clarification"
    DECISION = "decision"
    APPROVAL = "approval"


class InteractionState(StrEnum):
    PENDING = "pending"
    RESOLVED = "resolved"
    EXPIRED = "expired"
    CANCELLED = "cancelled"


class SideEffectState(StrEnum):
    NONE = "none"
    POSSIBLE = "possible"
    CONFIRMED = "confirmed"
    UNCERTAIN = "uncertain"


class InteractionPolicy(StrEnum):
    ASK_WHEN_BLOCKED = "ask_when_blocked"
    APPROVE_AFTER_SESSION_START = "approve_after_session_start"


def new_identifier(prefix: str) -> str:
    """Create a compact stable identifier using the domain prefix."""
    if not prefix or not prefix.isalpha():
        raise ValueError("identifier prefix must contain letters only")
    return f"{prefix}_{uuid.uuid4().hex}"


def stable_identifier(prefix: str, idempotency_key: str) -> str:
    """Derive a retry-stable ID without exposing the request key."""
    if not prefix or not prefix.isalpha():
        raise ValueError("identifier prefix must contain letters only")
    normalized_key = require_identifier(idempotency_key, "idempotency_key")
    value = uuid.uuid5(uuid.NAMESPACE_URL, f"lingfeng:{prefix}:{normalized_key}")
    return f"{prefix}_{value.hex}"


def utc_now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def require_identifier(value: str, field_name: str) -> str:
    normalized = str(value or "").strip()
    if not IDENTIFIER_PATTERN.fullmatch(normalized):
        raise ValueError(f"{field_name} is not a valid identifier")
    return normalized


def compact_summary(value: str, *, field_name: str = "summary") -> str:
    """Normalize a server-safe control summary and enforce its size budget."""
    normalized = " ".join(str(value or "").split())
    if not normalized:
        raise ValueError(f"{field_name} is required")
    if len(normalized) > MAX_SUMMARY_CHARS:
        raise ValueError(
            f"{field_name} exceeds the {MAX_SUMMARY_CHARS}-character control-plane limit"
        )
    return normalized


@dataclass(frozen=True, slots=True)
class WorkItemSpec:
    title: str
    objective: str
    acceptance_summary: str
    kind: WorkItemKind = WorkItemKind.TASK
    priority: int = 0
    target_node_id: str = "office-pc"
    local_workspace_ref: str = "office-default"
    interaction_policy: InteractionPolicy = InteractionPolicy.ASK_WHEN_BLOCKED
    authorized_side_effects_summary: str = "none"
    data_boundary_ack: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "title", compact_summary(self.title, field_name="title"))
        object.__setattr__(
            self,
            "objective",
            compact_summary(self.objective, field_name="objective"),
        )
        object.__setattr__(
            self,
            "acceptance_summary",
            compact_summary(self.acceptance_summary, field_name="acceptance_summary"),
        )
        object.__setattr__(
            self,
            "target_node_id",
            require_identifier(self.target_node_id, "target_node_id"),
        )
        object.__setattr__(
            self,
            "local_workspace_ref",
            require_identifier(self.local_workspace_ref, "local_workspace_ref"),
        )
        object.__setattr__(
            self,
            "interaction_policy",
            InteractionPolicy(self.interaction_policy),
        )
        object.__setattr__(
            self,
            "authorized_side_effects_summary",
            compact_summary(
                self.authorized_side_effects_summary,
                field_name="authorized_side_effects_summary",
            ),
        )
        if not isinstance(self.priority, int) or not -100 <= self.priority <= 100:
            raise ValueError("priority must be an integer between -100 and 100")
        if not self.data_boundary_ack:
            raise ValueError("data boundary acknowledgement is required")


@dataclass(frozen=True, slots=True)
class MissionAssignment:
    work_item_id: str
    mission_id: str
    kanban_task_id: str
    run_id: str
    objective: str
    acceptance_summary: str
    target_node_id: str
    local_workspace_ref: str
    runtime_kind: str = "ws"
    interaction_policy: InteractionPolicy = InteractionPolicy.ASK_WHEN_BLOCKED
    authorized_side_effects_summary: str = "none"

    def __post_init__(self) -> None:
        for name in (
            "work_item_id",
            "mission_id",
            "kanban_task_id",
            "run_id",
            "target_node_id",
            "local_workspace_ref",
        ):
            object.__setattr__(self, name, require_identifier(getattr(self, name), name))
        object.__setattr__(
            self,
            "objective",
            compact_summary(self.objective, field_name="objective"),
        )
        object.__setattr__(
            self,
            "acceptance_summary",
            compact_summary(self.acceptance_summary, field_name="acceptance_summary"),
        )
        object.__setattr__(
            self,
            "interaction_policy",
            InteractionPolicy(self.interaction_policy),
        )
        object.__setattr__(
            self,
            "authorized_side_effects_summary",
            compact_summary(
                self.authorized_side_effects_summary,
                field_name="authorized_side_effects_summary",
            ),
        )

    def to_payload(self) -> dict[str, Any]:
        return {
            "work_item_id": self.work_item_id,
            "mission_id": self.mission_id,
            "kanban_task_id": self.kanban_task_id,
            "run_id": self.run_id,
            "objective": self.objective,
            "acceptance_summary": self.acceptance_summary,
            "target_node_id": self.target_node_id,
            "local_workspace_ref": self.local_workspace_ref,
            "runtime_kind": self.runtime_kind,
            "interaction_policy": self.interaction_policy.value,
            "authorized_side_effects_summary": self.authorized_side_effects_summary,
        }


@dataclass(frozen=True, slots=True)
class RuntimeCapabilities:
    runtime_kind: str
    persistent_session: bool = False
    structured_events: bool = False
    interactive_input: bool = False
    approval_requests: bool = False
    clarification_requests: bool = False
    cancel: bool = False
    resume: bool = False
    worktree: bool = False
    subagents: bool = False
    agent_teams: bool = False
    attachments: bool = False
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_payload(self) -> dict[str, Any]:
        return {
            "runtime_kind": self.runtime_kind,
            "persistent_session": self.persistent_session,
            "structured_events": self.structured_events,
            "interactive_input": self.interactive_input,
            "approval_requests": self.approval_requests,
            "clarification_requests": self.clarification_requests,
            "cancel": self.cancel,
            "resume": self.resume,
            "worktree": self.worktree,
            "subagents": self.subagents,
            "agent_teams": self.agent_teams,
            "attachments": self.attachments,
            "metadata": dict(self.metadata),
        }
