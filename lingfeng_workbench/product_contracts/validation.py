"""Small validation helpers that keep control-plane records path- and secret-free."""

from __future__ import annotations

import re
from datetime import UTC, datetime
from typing import Any

IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
MAX_SUMMARY_CHARS = 800


def identifier(value: Any, field: str) -> str:
    normalized = str(value or "").strip()
    if not IDENTIFIER.fullmatch(normalized):
        raise ValueError(f"{field} must be an opaque identifier")
    return normalized


def summary(value: Any, field: str) -> str:
    normalized = " ".join(str(value or "").split())
    if not normalized:
        raise ValueError(f"{field} is required")
    if len(normalized) > MAX_SUMMARY_CHARS:
        raise ValueError(f"{field} exceeds the control-plane limit")
    return normalized


def positive_version(value: Any) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ValueError("version must be a positive integer")
    return value


def iso_timestamp(value: Any, field: str) -> str:
    normalized = str(value or "").strip()
    try:
        parsed = datetime.fromisoformat(normalized.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{field} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError(f"{field} must include a timezone")
    return parsed.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def opaque_workspace_ref(value: Any, node_id: str) -> str:
    normalized = str(value or "").strip()
    prefix = f"{identifier(node_id, 'node_id')}:"
    if not normalized.startswith(prefix):
        raise ValueError("local_workspace_ref must be scoped to its Node")
    opaque = normalized[len(prefix) :]
    if not IDENTIFIER.fullmatch(opaque):
        raise ValueError("local_workspace_ref must not contain an absolute path")
    return normalized


def sha256(value: Any) -> str:
    normalized = str(value or "").lower()
    if not SHA256.fullmatch(normalized):
        raise ValueError("sha256 must contain 64 lowercase hex characters")
    return normalized


def commit_sha(value: Any) -> str:
    normalized = str(value or "").lower()
    if not COMMIT_SHA.fullmatch(normalized):
        raise ValueError("commit_sha must contain 40 lowercase hex characters")
    return normalized
