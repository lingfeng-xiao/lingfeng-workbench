"""Agent-facing tool handlers bound to one service instance."""

from __future__ import annotations

import json
from typing import Any, Callable

from .domain import InteractionPolicy, WorkItemKind, WorkItemSpec
from .service import DATA_BOUNDARY_NOTICE, LingfengService


ToolHandler = Callable[[dict[str, Any]], str]


def build_tool_handlers(service: LingfengService) -> dict[str, ToolHandler]:
    def create_work_item(args: dict[str, Any], **_kwargs: Any) -> str:
        try:
            spec = WorkItemSpec(
                title=args.get("title", ""),
                objective=args.get("objective", ""),
                acceptance_summary=args.get("acceptance_summary", ""),
                kind=WorkItemKind(args.get("kind", WorkItemKind.TASK.value)),
                priority=args.get("priority", 0),
                target_node_id=args.get("target_node_id", "office-pc"),
                local_workspace_ref=args.get(
                    "local_workspace_ref",
                    "office-default",
                ),
                interaction_policy=InteractionPolicy(
                    args.get("interaction_policy", "ask_when_blocked")
                ),
                authorized_side_effects_summary=args.get(
                    "authorized_side_effects_summary",
                    "none",
                ),
                data_boundary_ack=args.get("data_boundary_ack", False),
            )
            created = service.create_work_item(
                spec,
                idempotency_key=args.get("idempotency_key", ""),
            )
            return _success(
                {
                    **created.to_dict(),
                    "data_boundary_notice": DATA_BOUNDARY_NOTICE,
                }
            )
        except (LookupError, TypeError, ValueError, RuntimeError) as exc:
            return _failure(str(exc))

    def status(args: dict[str, Any], **_kwargs: Any) -> str:
        del args
        try:
            return _success(service.status_snapshot())
        except RuntimeError as exc:
            return _failure(str(exc))

    def resolve_interaction(args: dict[str, Any], **_kwargs: Any) -> str:
        try:
            result = service.resolve_interaction(
                args.get("interaction_id", ""),
                args.get("response_summary", ""),
            )
            return _success(result)
        except (LookupError, TypeError, ValueError, RuntimeError) as exc:
            return _failure(str(exc))

    return {
        "lingfeng_create_work_item": create_work_item,
        "lingfeng_status": status,
        "lingfeng_resolve_interaction": resolve_interaction,
    }


def _success(result: Any) -> str:
    return json.dumps({"success": True, "result": result}, ensure_ascii=False)


def _failure(message: str) -> str:
    return json.dumps({"success": False, "error": message}, ensure_ascii=False)
