"""Application service coordinating Kanban and plugin-owned control state."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Protocol

from .domain import (
    InteractionKind,
    MissionAssignment,
    RunState,
    SideEffectState,
    WorkItemSpec,
    compact_summary,
    new_identifier,
    require_identifier,
    stable_identifier,
)
from .repositories import TaskRepository
from .storage import PluginStore


DATA_BOUNDARY_NOTICE = (
    "任务标题、简短目标、进度摘要和审批内容会保存在个人 Hermes 服务器；"
    "完整需求、公司代码、日志、报告、凭证和 Runtime 原始对话仅保存在当前电脑。"
)


@dataclass(frozen=True, slots=True)
class CreatedWorkItem:
    work_item_id: str
    mission_id: str
    work_item_task_id: str
    mission_task_id: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


class EventNotifier(Protocol):
    def notify(self, event_type: str, message: str) -> None: ...


class NullEventNotifier:
    def notify(self, event_type: str, message: str) -> None:
        del event_type, message


class LingfengService:
    """Owns cross-repository invariants for the external-runtime lane."""

    def __init__(
        self,
        store: PluginStore,
        tasks: TaskRepository,
        notifier: EventNotifier | None = None,
    ) -> None:
        self.store = store
        self.tasks = tasks
        self.notifier = notifier or NullEventNotifier()

    def create_work_item(
        self,
        spec: WorkItemSpec,
        *,
        idempotency_key: str,
    ) -> CreatedWorkItem:
        request_key = require_identifier(idempotency_key, "idempotency_key")
        existing = self.store.get_idempotent_result("create_work_item", request_key)
        if existing is not None:
            return CreatedWorkItem(**existing)

        work_item_id = stable_identifier("wi", request_key)
        mission_id = stable_identifier("mi", f"{request_key}:mission-1")
        work_item_task_id = self.tasks.create_work_item_card(
            title=spec.title,
            body=self._work_item_body(spec, work_item_id),
            priority=spec.priority,
            idempotency_key=f"{request_key}:work-item",
        )
        mission_task_id = self.tasks.create_mission_card(
            title=f"执行：{spec.title}",
            body=self._mission_body(spec, work_item_id, mission_id),
            priority=spec.priority,
            idempotency_key=f"{request_key}:mission-1",
        )
        self.store.bind_work_item(
            work_item_id=work_item_id,
            kanban_task_id=work_item_task_id,
            target_node_id=spec.target_node_id,
            local_workspace_ref=spec.local_workspace_ref,
            objective=spec.objective,
            acceptance_summary=spec.acceptance_summary,
            interaction_policy=spec.interaction_policy.value,
            authorized_side_effects_summary=spec.authorized_side_effects_summary,
        )
        self.store.bind_mission(
            work_item_id=work_item_id,
            mission_id=mission_id,
            kanban_task_id=mission_task_id,
            target_node_id=spec.target_node_id,
            local_workspace_ref=spec.local_workspace_ref,
            objective=spec.objective,
            acceptance_summary=spec.acceptance_summary,
            interaction_policy=spec.interaction_policy.value,
            authorized_side_effects_summary=spec.authorized_side_effects_summary,
        )
        created = CreatedWorkItem(
            work_item_id=work_item_id,
            mission_id=mission_id,
            work_item_task_id=work_item_task_id,
            mission_task_id=mission_task_id,
        )
        persisted = self.store.record_idempotent_result(
            "create_work_item",
            request_key,
            created.to_dict(),
        )
        return CreatedWorkItem(**persisted)

    def claim_next_mission(self, node_id: str) -> MissionAssignment | None:
        normalized_node_id = require_identifier(node_id, "node_id")
        for binding in self.store.list_mission_bindings(normalized_node_id):
            mission_id = binding["mission_id"]
            if self.store.get_active_run_for_mission(mission_id) is not None:
                continue
            if self.tasks.task_status(binding["kanban_task_id"]) != "ready":
                continue
            claimed = self.tasks.claim_task(binding["kanban_task_id"], normalized_node_id)
            if claimed is None:
                continue
            run_id = self.store.create_run(
                mission_id=mission_id,
                kanban_task_id=claimed.task_id,
                kanban_run_id=claimed.kanban_run_id,
                node_id=normalized_node_id,
                runtime_kind=binding["runtime_kind"],
            )
            return self.store.assignment_from_binding(binding, run_id=run_id)
        return None

    def record_run_started(
        self,
        run_id: str,
        *,
        runtime_session_ref: str | None,
    ) -> None:
        self._require_run(run_id)
        updated = self.store.update_run(
            run_id,
            state=RunState.RUNNING,
            runtime_session_ref=runtime_session_ref,
        )
        if not updated:  # pragma: no cover - guarded above
            raise RuntimeError(f"run disappeared during update: {run_id}")
        self.notifier.notify(
            "run_started",
            f"lingfeng-workbench 已在办公电脑开始执行（Run：{run_id}）",
        )

    def record_progress(
        self,
        run_id: str,
        summary: str,
        *,
        checkpoint_id: str | None = None,
        notify_checkpoint: bool = False,
    ) -> None:
        run = self._require_active_run(run_id)
        normalized = compact_summary(summary, field_name="progress_summary")
        state = RunState(run["state"])
        if state in {RunState.ASSIGNED, RunState.STARTING}:
            state = RunState.RUNNING
        self.store.update_run(
            run_id,
            state=state,
            checkpoint_id=checkpoint_id,
            progress_summary=normalized,
        )
        if notify_checkpoint:
            self.notifier.notify(
                "checkpoint",
                f"lingfeng-workbench 进度：{normalized}（Run：{run_id}）",
            )

    def create_interaction(
        self,
        run_id: str,
        *,
        checkpoint_id: str,
        kind: InteractionKind,
        prompt_summary: str,
        options: list[str] | None = None,
        risk_summary: str | None = None,
        expires_at: str | None = None,
    ) -> str:
        self._require_active_run(run_id)
        checkpoint = require_identifier(checkpoint_id, "checkpoint_id")
        prompt = compact_summary(prompt_summary, field_name="prompt_summary")
        normalized_options = [
            compact_summary(option, field_name="interaction option")
            for option in (options or [])
        ]
        if len(normalized_options) > 10:
            raise ValueError("interaction supports at most 10 options")
        normalized_risk = (
            compact_summary(risk_summary, field_name="risk_summary")
            if risk_summary
            else None
        )
        interaction_id = self.store.create_interaction(
            run_id=run_id,
            checkpoint_id=checkpoint,
            kind=kind,
            prompt_summary=prompt,
            options=normalized_options,
            risk_summary=normalized_risk,
            expires_at=expires_at,
        )
        waiting_state = (
            RunState.WAITING_APPROVAL
            if kind is InteractionKind.APPROVAL
            else RunState.WAITING_INTERACTION
        )
        self.store.update_run(
            run_id,
            state=waiting_state,
            checkpoint_id=checkpoint,
            progress_summary=prompt,
        )
        if kind is InteractionKind.APPROVAL:
            notification = (
                f"lingfeng-workbench 需要审批：{prompt}\n"
                "回复 /lf y 批准，/lf n 拒绝。"
            )
        else:
            notification = (
                f"lingfeng-workbench 需要补充信息：{prompt}"
                f"（Interaction：{interaction_id}）"
            )
        self.notifier.notify("interaction_required", notification)
        return interaction_id

    def resolve_interaction(
        self,
        interaction_id: str,
        response_summary: str,
    ) -> dict[str, str]:
        normalized_id = require_identifier(interaction_id, "interaction_id")
        response = compact_summary(response_summary, field_name="response_summary")
        interaction = self.store.get_interaction(normalized_id)
        if interaction is None:
            raise LookupError(f"interaction not found: {normalized_id}")
        if not self.store.resolve_interaction(normalized_id, response):
            raise ValueError("interaction is no longer pending")
        self.store.update_run(
            interaction["run_id"],
            state=RunState.RUNNING,
            progress_summary="用户交互已完成，等待本地 Runtime 恢复",
        )
        return {
            "interaction_id": normalized_id,
            "run_id": interaction["run_id"],
            "checkpoint_id": interaction["checkpoint_id"],
            "response_summary": response,
        }

    def complete_run(self, run_id: str, result_summary: str) -> None:
        run = self._require_active_run(run_id)
        summary = compact_summary(result_summary, field_name="result_summary")
        if not self.tasks.complete_task(
            run["kanban_task_id"],
            kanban_run_id=run["kanban_run_id"],
            summary=summary,
        ):
            raise RuntimeError("Kanban rejected completion for the bound run")
        self.store.update_run(
            run_id,
            state=RunState.COMPLETED,
            result_summary=summary,
        )
        self.notifier.notify(
            "run_completed",
            f"lingfeng-workbench 任务已完成：{summary}（Run：{run_id}）",
        )

    def fail_run(
        self,
        run_id: str,
        reason: str,
        *,
        interrupted: bool = False,
        side_effect_state: SideEffectState = SideEffectState.NONE,
    ) -> None:
        run = self._require_active_run(run_id)
        summary = compact_summary(reason, field_name="failure_summary")
        if not self.tasks.fail_task(
            run["kanban_task_id"],
            kanban_run_id=run["kanban_run_id"],
            reason=summary,
        ):
            raise RuntimeError("Kanban rejected failure for the bound run")
        state = RunState.INTERRUPTED if interrupted else RunState.FAILED
        self.store.update_run(
            run_id,
            state=state,
            result_summary=summary,
            side_effect_state=side_effect_state,
        )
        self.notifier.notify(
            "run_interrupted" if interrupted else "run_failed",
            f"lingfeng-workbench 执行未完成：{summary}（Run：{run_id}）",
        )

    def status_snapshot(self) -> dict[str, Any]:
        return self.store.status_snapshot()

    def _require_run(self, run_id: str) -> dict[str, Any]:
        normalized = require_identifier(run_id, "run_id")
        run = self.store.get_run(normalized)
        if run is None:
            raise LookupError(f"run not found: {normalized}")
        return run

    def _require_active_run(self, run_id: str) -> dict[str, Any]:
        run = self._require_run(run_id)
        if RunState(run["state"]).is_terminal:
            raise ValueError(f"run is already terminal: {run_id}")
        return run

    @staticmethod
    def _work_item_body(spec: WorkItemSpec, work_item_id: str) -> str:
        return (
            f"WorkItem: {work_item_id}\n\n目标：{spec.objective}\n\n"
            f"验收摘要：{spec.acceptance_summary}\n\n数据边界：{DATA_BOUNDARY_NOTICE}"
        )

    @staticmethod
    def _mission_body(
        spec: WorkItemSpec,
        work_item_id: str,
        mission_id: str,
    ) -> str:
        return (
            f"Mission: {mission_id}\nWorkItem: {work_item_id}\n\n"
            f"目标：{spec.objective}\n\n验收摘要：{spec.acceptance_summary}\n\n"
            f"目标节点：{spec.target_node_id}\n"
            f"本地工作区引用：{spec.local_workspace_ref}\n\n"
            f"首次派发声明：{DATA_BOUNDARY_NOTICE}"
        )
