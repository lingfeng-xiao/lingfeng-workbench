"""Versioned NDJSON protocol for the office node worker stream."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Mapping

from .domain import new_identifier, require_identifier, utc_now_iso


PROTOCOL_VERSION = "1.0"
MAX_MESSAGE_BYTES = 64 * 1024


class MessageType(StrEnum):
    HELLO = "HELLO"
    WELCOME = "WELCOME"
    HEARTBEAT = "HEARTBEAT"
    CLAIM_REQUEST = "CLAIM_REQUEST"
    MISSION_ASSIGNED = "MISSION_ASSIGNED"
    NO_MISSION = "NO_MISSION"
    RUN_STARTED = "RUN_STARTED"
    RUN_EVENT = "RUN_EVENT"
    INTERACTION_REQUIRED = "INTERACTION_REQUIRED"
    INTERACTION_RESPONSE = "INTERACTION_RESPONSE"
    RUN_COMPLETED = "RUN_COMPLETED"
    RUN_FAILED = "RUN_FAILED"
    RUN_INTERRUPTED = "RUN_INTERRUPTED"
    CANCEL = "CANCEL"
    ACK = "ACK"
    ERROR = "ERROR"


@dataclass(frozen=True, slots=True)
class Correlation:
    work_item_id: str | None = None
    mission_id: str | None = None
    run_id: str | None = None

    def __post_init__(self) -> None:
        for name in ("work_item_id", "mission_id", "run_id"):
            value = getattr(self, name)
            if value is not None:
                object.__setattr__(self, name, require_identifier(value, name))

    def to_dict(self) -> dict[str, str]:
        return {
            key: value
            for key, value in {
                "work_item_id": self.work_item_id,
                "mission_id": self.mission_id,
                "run_id": self.run_id,
            }.items()
            if value is not None
        }


@dataclass(frozen=True, slots=True)
class Envelope:
    message_type: MessageType
    node_id: str
    payload: dict[str, Any] = field(default_factory=dict)
    correlation: Correlation = field(default_factory=Correlation)
    sequence: int = 0
    message_id: str = field(default_factory=lambda: new_identifier("msg"))
    sent_at: str = field(default_factory=utc_now_iso)
    protocol_version: str = PROTOCOL_VERSION

    def __post_init__(self) -> None:
        object.__setattr__(self, "node_id", require_identifier(self.node_id, "node_id"))
        object.__setattr__(self, "message_id", require_identifier(self.message_id, "message_id"))
        if self.protocol_version != PROTOCOL_VERSION:
            raise ValueError(
                f"unsupported protocol version {self.protocol_version!r}; expected {PROTOCOL_VERSION!r}"
            )
        if not isinstance(self.sequence, int) or self.sequence < 0:
            raise ValueError("sequence must be a non-negative integer")
        if not isinstance(self.payload, dict):
            raise ValueError("payload must be an object")

    def to_dict(self) -> dict[str, Any]:
        return {
            "protocol_version": self.protocol_version,
            "message_id": self.message_id,
            "message_type": self.message_type.value,
            "sequence": self.sequence,
            "sent_at": self.sent_at,
            "node_id": self.node_id,
            "correlation": self.correlation.to_dict(),
            "payload": self.payload,
        }

    def to_json(self) -> str:
        raw = json.dumps(self.to_dict(), ensure_ascii=False, separators=(",", ":"))
        if len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
            raise ValueError(f"message exceeds {MAX_MESSAGE_BYTES} bytes")
        return raw

    @classmethod
    def from_json(cls, raw: str) -> "Envelope":
        if not isinstance(raw, str):
            raise ValueError("message must be text")
        if len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
            raise ValueError(f"message exceeds {MAX_MESSAGE_BYTES} bytes")
        try:
            decoded = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError(f"malformed JSON: {exc.msg}") from exc
        if not isinstance(decoded, Mapping):
            raise ValueError("message root must be an object")
        required = {
            "protocol_version",
            "message_id",
            "message_type",
            "sequence",
            "sent_at",
            "node_id",
            "correlation",
            "payload",
        }
        missing = sorted(required.difference(decoded))
        if missing:
            raise ValueError(f"message missing fields: {', '.join(missing)}")
        unknown = sorted(set(decoded).difference(required))
        if unknown:
            raise ValueError(f"message contains unknown fields: {', '.join(unknown)}")
        correlation_value = decoded["correlation"]
        if not isinstance(correlation_value, Mapping):
            raise ValueError("correlation must be an object")
        correlation_unknown = sorted(
            set(correlation_value).difference({"work_item_id", "mission_id", "run_id"})
        )
        if correlation_unknown:
            raise ValueError(
                f"correlation contains unknown fields: {', '.join(correlation_unknown)}"
            )
        try:
            message_type = MessageType(decoded["message_type"])
        except (TypeError, ValueError) as exc:
            raise ValueError(f"unknown message_type: {decoded['message_type']!r}") from exc
        return cls(
            protocol_version=str(decoded["protocol_version"]),
            message_id=str(decoded["message_id"]),
            message_type=message_type,
            sequence=decoded["sequence"],
            sent_at=str(decoded["sent_at"]),
            node_id=str(decoded["node_id"]),
            correlation=Correlation(
                work_item_id=correlation_value.get("work_item_id"),
                mission_id=correlation_value.get("mission_id"),
                run_id=correlation_value.get("run_id"),
            ),
            payload=dict(decoded["payload"]) if isinstance(decoded["payload"], Mapping) else decoded["payload"],
        )


def response_envelope(
    request: Envelope,
    message_type: MessageType,
    payload: dict[str, Any] | None = None,
    *,
    correlation: Correlation | None = None,
) -> Envelope:
    response_payload = dict(payload or {})
    response_payload.setdefault("request_message_id", request.message_id)
    return Envelope(
        message_type=message_type,
        node_id=request.node_id,
        payload=response_payload,
        correlation=correlation or request.correlation,
        sequence=request.sequence,
    )

