"""Runtime-neutral execution contracts used by office nodes."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any, Iterator, Protocol

from ..domain import MissionAssignment, RuntimeCapabilities


class RuntimeEventType(StrEnum):
    STARTED = "started"
    PROGRESS = "progress"
    HEARTBEAT = "heartbeat"
    INTERACTION_REQUIRED = "interaction_required"
    COMPLETED = "completed"
    FAILED = "failed"
    INTERRUPTED = "interrupted"


@dataclass(frozen=True, slots=True)
class RuntimeEvent:
    event_type: RuntimeEventType
    summary: str | None = None
    runtime_session_ref: str | None = None
    checkpoint_id: str | None = None
    payload: dict[str, Any] = field(default_factory=dict)


class RuntimeAdapter(Protocol):
    def probe(self) -> tuple[bool, str]: ...

    def capabilities(self) -> RuntimeCapabilities: ...

    def run(
        self,
        assignment: MissionAssignment,
        *,
        workspace: Path,
        local_run_dir: Path,
    ) -> Iterator[RuntimeEvent]: ...

    def resume(
        self,
        *,
        runtime_session_ref: str,
        input_text: str,
        workspace: Path,
        local_run_dir: Path,
    ) -> Iterator[RuntimeEvent]: ...

    def cancel(self) -> None: ...
