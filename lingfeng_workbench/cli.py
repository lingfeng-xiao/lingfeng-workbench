"""`hermes lingfeng` command tree."""

from __future__ import annotations

import argparse
import json
from typing import Callable

from .domain import InteractionPolicy, WorkItemKind, WorkItemSpec
from .service import LingfengService
from .storage import PluginStore
from .worker_stream import WorkerStream, run_worker_stream


def build_cli(
    service: LingfengService,
    store: PluginStore,
) -> tuple[Callable[[argparse.ArgumentParser], None], Callable[[argparse.Namespace], int]]:
    def register_cli(parser: argparse.ArgumentParser) -> None:
        commands = parser.add_subparsers(dest="lingfeng_command", required=True)
        for name in ("status", "nodes", "tasks", "runs", "doctor"):
            commands.add_parser(name)
        create = commands.add_parser("create", help="Create a short control-plane task")
        create.add_argument("--title", required=True)
        create.add_argument("--objective", required=True)
        create.add_argument("--acceptance", required=True)
        create.add_argument("--kind", choices=[item.value for item in WorkItemKind], default="task")
        create.add_argument("--priority", type=int, default=0)
        create.add_argument("--node", default="office-pc")
        create.add_argument("--workspace", default="office-default")
        create.add_argument(
            "--interaction-policy",
            choices=[policy.value for policy in InteractionPolicy],
            default=InteractionPolicy.ASK_WHEN_BLOCKED.value,
        )
        create.add_argument("--authorized-effects", default="none")
        create.add_argument("--idempotency-key", required=True)
        create.add_argument("--ack-data-boundary", action="store_true", required=True)
        worker = commands.add_parser(
            "worker-stream",
            help="NDJSON endpoint for a forced-command SSH connection",
        )
        worker.add_argument("--node", required=True)
        parser.set_defaults(func=handle_cli)

    def handle_cli(args: argparse.Namespace) -> int:
        command = args.lingfeng_command
        if command == "worker-stream":
            return run_worker_stream(
                WorkerStream(
                    service,
                    store,
                    expected_node_id=args.node,
                )
            )
        if command == "create":
            created = service.create_work_item(
                WorkItemSpec(
                    title=args.title,
                    objective=args.objective,
                    acceptance_summary=args.acceptance,
                    kind=WorkItemKind(args.kind),
                    priority=args.priority,
                    target_node_id=args.node,
                    local_workspace_ref=args.workspace,
                    interaction_policy=InteractionPolicy(args.interaction_policy),
                    authorized_side_effects_summary=args.authorized_effects,
                    data_boundary_ack=args.ack_data_boundary,
                ),
                idempotency_key=args.idempotency_key,
            )
            print(json.dumps(created.to_dict(), ensure_ascii=False, indent=2))
            return 0
        snapshot = service.status_snapshot()
        if command == "status":
            result = snapshot
        elif command == "nodes":
            result = snapshot["nodes"]
        elif command == "tasks":
            result = snapshot["counts"]
        elif command == "runs":
            result = snapshot["recent_runs"]
        elif command == "doctor":
            result = {
                "ok": True,
                "database": str(store.database_path),
                "protocol": "1.0",
                "data_boundary": "server stores control summaries only",
            }
        else:  # pragma: no cover - argparse rejects this
            return 2
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0

    return register_cli, handle_cli
