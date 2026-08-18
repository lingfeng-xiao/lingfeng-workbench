"""`lingfeng-node` console entry point."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from ..protocol import MessageType
from ..runtime.ws_cli import WsCliAdapter
from .config import NodeConfig
from .runner import NodeRunner
from .ssh_stream import SshWorkerClient
from .state import NodeState


def default_config_path() -> Path:
    app_data = os.environ.get("APPDATA")
    root = Path(app_data) if app_data else Path.home() / ".config"
    return root / "lingfeng-workbench" / "node.json"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="lingfeng-node")
    parser.add_argument("--config", type=Path, default=default_config_path())
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser(
        "init",
        help="Create local config and an independent SSH key",
    )
    init.add_argument(
        "--desktop-connection",
        type=Path,
        default=Path(os.environ.get("APPDATA", "")) / "Hermes" / "connection.json",
    )
    init.add_argument(
        "--workspace",
        action="append",
        required=True,
        metavar="REF=PATH",
    )
    init.add_argument("--force", action="store_true")
    run = commands.add_parser("run", help="Connect and process office missions")
    run.add_argument("--once", action="store_true")
    commands.add_parser("doctor", help="Validate local config and WS availability")
    commands.add_parser("status", help="Show recent local-only runs")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "init":
            return _initialize_config(args)
        config = NodeConfig.load(args.config)
        state = NodeState(config.state_dir)
        runtime = WsCliAdapter(executable=config.ws_executable)
        if args.command == "doctor":
            ok, detail = runtime.probe()
            print(
                json.dumps(
                    {
                        "ok": ok,
                        "node_id": config.node_id,
                        "runtime": "ws",
                        "runtime_detail": detail,
                        "workspace_refs": sorted(config.workspaces),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0 if ok else 1
        if args.command == "status":
            print(json.dumps(state.snapshot(), ensure_ascii=False, indent=2))
            return 0
        client = SshWorkerClient(config)
        with client:
            runner = NodeRunner(config, state, runtime, client)
            runner.handshake()
            if args.once:
                runner.run_once()
            else:
                while True:
                    if not runner.run_once():
                        response = runner._exchange(MessageType.HEARTBEAT)
                        if response.message_type is MessageType.INTERACTION_RESPONSE:
                            raise RuntimeError(
                                "received an interaction response without an active run"
                            )
                        runner._expect_ack(response)
                        time.sleep(config.poll_seconds)
        return 0
    except KeyboardInterrupt:
        return 130
    except (ConnectionError, LookupError, OSError, RuntimeError, ValueError) as exc:
        print(f"lingfeng-node: {exc}", file=sys.stderr)
        return 1


def _initialize_config(args: argparse.Namespace) -> int:
    config_path: Path = args.config.resolve()
    if config_path.exists() and not args.force:
        raise ValueError(f"config already exists: {config_path}; use --force to replace")
    desktop_path: Path = args.desktop_connection.resolve()
    try:
        desktop = json.loads(desktop_path.read_text(encoding="utf-8"))
        remote = desktop["remote"]
        host = str(remote["host"]).strip()
        user = str(remote["user"]).strip()
        port = int(remote["port"])
    except (FileNotFoundError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise ValueError("cannot read non-secret SSH fields from Hermes Desktop") from exc
    workspaces: dict[str, str] = {}
    for item in args.workspace:
        if "=" not in item:
            raise ValueError("workspace must use REF=PATH")
        workspace_ref, raw_path = item.split("=", 1)
        path = Path(raw_path).expanduser().resolve()
        if not path.is_dir():
            raise ValueError(f"workspace path does not exist: {workspace_ref}")
        workspaces[workspace_ref] = str(path)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    key_path = config_path.parent / "lingfeng_node_ed25519"
    if not key_path.exists():
        result = subprocess.run(
            [
                "ssh-keygen",
                "-q",
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                "lingfeng-node:office-pc",
                "-f",
                str(key_path),
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError("failed to generate the independent node SSH key")
    payload = {
        "node_id": "office-pc",
        "display_name": "办公电脑",
        "ssh_host": host,
        "ssh_user": user,
        "ssh_port": port,
        "ssh_key_path": str(key_path),
        "state_dir": str(config_path.parent / "state"),
        "workspaces": workspaces,
        "ws_executable": "ws",
        "remote_command": (
            "/home/lingfeng/.hermes/hermes-agent/venv/bin/hermes "
            "lingfeng worker-stream --node office-pc"
        ),
        "poll_seconds": 10,
    }
    config_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    try:
        config_path.chmod(0o600)
    except OSError:
        pass
    print(
        json.dumps(
            {
                "ok": True,
                "config": str(config_path),
                "public_key": str(key_path) + ".pub",
                "workspace_refs": sorted(workspaces),
                "next": "enroll the public key as a forced command on Hermes",
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
