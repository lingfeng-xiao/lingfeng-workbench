"""Hermes Plugin API registration."""

from __future__ import annotations

from .cli import build_cli
from .notifier import HermesMessageNotifier
from .repositories import HermesKanbanTaskRepository
from .schemas import (
    CREATE_WORK_ITEM_SCHEMA,
    RESOLVE_INTERACTION_SCHEMA,
    STATUS_SCHEMA,
)
from .service import LingfengService
from .slash_commands import build_lingfeng_slash_handler
from .storage import PluginStore
from .tools import build_tool_handlers


def register(ctx) -> None:
    store = PluginStore(ctx.state.data_dir / "lingfeng.db")
    task_repository = HermesKanbanTaskRepository(
        board=ctx.get_config("board", "lingfeng-workbench"),
        external_assignee=ctx.get_config(
            "external_assignee",
            "lingfeng-external",
        ),
    )
    notifier = HermesMessageNotifier(
        ctx.get_config("notification_target", "weixin")
    )
    service = LingfengService(store, task_repository, notifier)
    handlers = build_tool_handlers(service)
    registrations = (
        (
            "lingfeng_create_work_item",
            CREATE_WORK_ITEM_SCHEMA,
            "📥",
        ),
        ("lingfeng_status", STATUS_SCHEMA, "📊"),
        (
            "lingfeng_resolve_interaction",
            RESOLVE_INTERACTION_SCHEMA,
            "✅",
        ),
    )
    for name, schema, emoji in registrations:
        ctx.register_tool(
            name=name,
            toolset="lingfeng-workbench",
            schema=schema,
            handler=handlers[name],
            emoji=emoji,
        )
    slash_handler = build_lingfeng_slash_handler(service)
    ctx.register_command(
        "lingfeng",
        handler=slash_handler,
        description="Approve, reject, or inspect lingfeng-workbench without an LLM turn.",
        args_hint="status | approve <interaction_id> | reject <interaction_id>",
    )
    ctx.register_command(
        "lf",
        handler=slash_handler,
        description="Short lingfeng-workbench approval command.",
        args_hint="y | n | s",
    )
    register_cli, handle_cli = build_cli(service, store)
    ctx.register_cli_command(
        name="lingfeng",
        help="Manage external agent-runtime work",
        setup_fn=register_cli,
        handler_fn=handle_cli,
        description="Control office-PC missions without uploading local work context.",
    )
