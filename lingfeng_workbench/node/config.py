"""Explicit local-only node configuration and workspace allow-list."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..domain import require_identifier


@dataclass(frozen=True, slots=True)
class NodeConfig:
    node_id: str
    display_name: str
    ssh_host: str
    ssh_user: str
    ssh_port: int
    ssh_key_path: Path
    state_dir: Path
    workspaces: dict[str, Path]
    ws_executable: str = "ws"
    remote_command: str = (
        "/home/lingfeng/.hermes/hermes-agent/venv/bin/hermes "
        "lingfeng worker-stream --node office-pc"
    )
    poll_seconds: float = 10.0

    @classmethod
    def load(cls, path: Path) -> "NodeConfig":
        try:
            decoded = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ValueError(f"node config does not exist: {path}") from exc
        except json.JSONDecodeError as exc:
            raise ValueError(f"node config is invalid JSON: {exc.msg}") from exc
        if not isinstance(decoded, dict):
            raise ValueError("node config root must be an object")
        required = {
            "node_id",
            "display_name",
            "ssh_host",
            "ssh_user",
            "ssh_port",
            "ssh_key_path",
            "state_dir",
            "workspaces",
        }
        optional = {"ws_executable", "remote_command", "poll_seconds"}
        missing = sorted(required.difference(decoded))
        unknown = sorted(set(decoded).difference(required | optional))
        if missing:
            raise ValueError(f"node config missing fields: {', '.join(missing)}")
        if unknown:
            raise ValueError(f"node config contains unknown fields: {', '.join(unknown)}")
        workspaces = cls._parse_workspaces(decoded["workspaces"])
        node_id = require_identifier(decoded["node_id"], "node_id")
        remote_command = decoded.get(
            "remote_command",
            cls.__dataclass_fields__["remote_command"].default,
        )
        expected_suffix = f"lingfeng worker-stream --node {node_id}"
        if not str(remote_command).endswith(expected_suffix):
            raise ValueError(
                "remote_command must end with the worker-stream binding for node_id"
            )
        port = decoded["ssh_port"]
        if not isinstance(port, int) or not 1 <= port <= 65535:
            raise ValueError("ssh_port must be an integer between 1 and 65535")
        return cls(
            node_id=node_id,
            display_name=str(decoded["display_name"]).strip(),
            ssh_host=str(decoded["ssh_host"]).strip(),
            ssh_user=str(decoded["ssh_user"]).strip(),
            ssh_port=port,
            ssh_key_path=Path(decoded["ssh_key_path"]).expanduser().resolve(),
            state_dir=Path(decoded["state_dir"]).expanduser().resolve(),
            workspaces=workspaces,
            ws_executable=str(decoded.get("ws_executable", "ws")),
            remote_command=str(remote_command),
            poll_seconds=float(decoded.get("poll_seconds", 10.0)),
        )

    @staticmethod
    def _parse_workspaces(value: Any) -> dict[str, Path]:
        if not isinstance(value, dict) or not value:
            raise ValueError("workspaces must be a non-empty object")
        parsed: dict[str, Path] = {}
        for workspace_ref, raw_path in value.items():
            normalized_ref = require_identifier(workspace_ref, "workspace reference")
            path = Path(str(raw_path)).expanduser().resolve()
            if not path.is_dir():
                raise ValueError(f"workspace does not exist: {normalized_ref}")
            parsed[normalized_ref] = path
        return parsed

    def resolve_workspace(self, workspace_ref: str) -> Path:
        normalized = require_identifier(workspace_ref, "local_workspace_ref")
        try:
            return self.workspaces[normalized]
        except KeyError as exc:
            raise ValueError(f"workspace reference is not allow-listed: {normalized}") from exc
