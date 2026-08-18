"""Deterministic Gateway commands for control actions during model outages."""

from __future__ import annotations

from collections.abc import Callable

from .service import LingfengService


SlashCommandHandler = Callable[[str], str]


def build_lingfeng_slash_handler(service: LingfengService) -> SlashCommandHandler:
    def lingfeng_command(raw_args: str) -> str:
        arguments = raw_args.strip().split()
        if not arguments:
            return _usage(service)
        action = arguments[0].lower()
        if action in {"status", "s"} and len(arguments) == 1:
            return _status(service)
        if action not in {"approve", "reject", "y", "n"}:
            return _usage(service)
        if len(arguments) == 1:
            interaction_id_or_error = _find_unique_pending_interaction(service)
            if not interaction_id_or_error.startswith("ix_"):
                return interaction_id_or_error
            interaction_id = interaction_id_or_error
        elif len(arguments) == 2:
            interaction_id = arguments[1]
        else:
            return _usage(service)
        approved = action in {"approve", "y"}
        response_summary = "批准本次" if approved else "拒绝"
        try:
            resolved = service.resolve_interaction(
                interaction_id,
                response_summary,
            )
        except LookupError:
            return "未处理：找不到这个 Interaction。"
        except ValueError:
            return "未处理：该 Interaction 不再处于待处理状态，可能已被消费。"
        except (TypeError, RuntimeError):
            return "未处理：审批状态暂时不可用，请先发送 /lingfeng status。"
        action_text = "已批准" if approved else "已拒绝"
        return (
            f"{action_text} Interaction {resolved['interaction_id']}。"
            f"办公 Node 将在下一次心跳处理原 Run {resolved['run_id']}。"
        )

    return lingfeng_command


def _usage(service: LingfengService) -> str:
    pending = service.status_snapshot()["pending_interactions"]
    suffix = f" 当前待处理：{len(pending)}。" if pending else " 当前没有待处理审批。"
    return (
        "快捷审批：/lf y 批准，/lf n 拒绝，/lf s 查看。"
        "多个审批时使用 /lingfeng approve|reject <interaction_id>。"
        f"{suffix}"
    )


def _status(service: LingfengService) -> str:
    pending = service.status_snapshot()["pending_interactions"]
    if not pending:
        return "lingfeng-workbench 当前没有待处理审批。"
    lines = [f"待处理审批：{len(pending)}"]
    for interaction in pending[:5]:
        lines.append(
            f"{interaction['interaction_id']}：{interaction['prompt_summary']}"
        )
    return "\n".join(lines)


def _find_unique_pending_interaction(service: LingfengService) -> str:
    pending = service.status_snapshot()["pending_interactions"]
    if not pending:
        return "当前没有待处理审批。"
    if len(pending) > 1:
        return (
            f"当前有 {len(pending)} 个待处理审批，未执行快捷操作。"
            "请发送 /lf s 查看后使用带 Interaction ID 的完整命令。"
        )
    return str(pending[0]["interaction_id"])
