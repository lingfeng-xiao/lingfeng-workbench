"""Task repository boundary and Hermes Kanban adapter."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class ClaimedTask:
    task_id: str
    kanban_run_id: int | None


class TaskRepository(Protocol):
    def create_work_item_card(
        self,
        *,
        title: str,
        body: str,
        priority: int,
        idempotency_key: str,
    ) -> str: ...

    def create_mission_card(
        self,
        *,
        title: str,
        body: str,
        priority: int,
        idempotency_key: str,
        dependency_task_ids: tuple[str, ...] = (),
    ) -> str: ...

    def claim_task(self, task_id: str, node_id: str) -> ClaimedTask | None: ...

    def complete_task(
        self,
        task_id: str,
        *,
        kanban_run_id: int | None,
        summary: str,
    ) -> bool: ...

    def fail_task(
        self,
        task_id: str,
        *,
        kanban_run_id: int | None,
        reason: str,
    ) -> bool: ...

    def task_status(self, task_id: str) -> str | None: ...


class HermesKanbanTaskRepository:
    """Uses public hermes_cli.kanban_db functions; never writes Kanban SQL."""

    def __init__(
        self,
        *,
        board: str = "lingfeng-workbench",
        external_assignee: str = "lingfeng-external",
    ) -> None:
        from hermes_cli import kanban_db as kanban

        self._kanban = kanban
        self.board = board
        self.external_assignee = external_assignee
        kanban.create_board(
            board,
            name="lingfeng-workbench",
            description="External agent runtime missions for lingfeng-workbench",
            icon="⚡",
            color="#4f46e5",
        )

    def create_work_item_card(
        self,
        *,
        title: str,
        body: str,
        priority: int,
        idempotency_key: str,
    ) -> str:
        with self._kanban.connect_closing(board=self.board) as connection:
            return self._kanban.create_task(
                connection,
                title=title,
                body=body,
                assignee="lingfeng-control",
                created_by="lingfeng-workbench",
                priority=priority,
                triage=True,
                idempotency_key=idempotency_key,
                board=self.board,
            )

    def create_mission_card(
        self,
        *,
        title: str,
        body: str,
        priority: int,
        idempotency_key: str,
        dependency_task_ids: tuple[str, ...] = (),
    ) -> str:
        with self._kanban.connect_closing(board=self.board) as connection:
            return self._kanban.create_task(
                connection,
                title=title,
                body=body,
                assignee=self.external_assignee,
                created_by="lingfeng-workbench",
                priority=priority,
                parents=dependency_task_ids,
                idempotency_key=idempotency_key,
                board=self.board,
            )

    def claim_task(self, task_id: str, node_id: str) -> ClaimedTask | None:
        with self._kanban.connect_closing(board=self.board) as connection:
            task = self._kanban.claim_task(
                connection,
                task_id,
                claimer=f"lingfeng-node:{node_id}",
            )
            if task is None:
                return None
            return ClaimedTask(task_id=task.id, kanban_run_id=task.current_run_id)

    def complete_task(
        self,
        task_id: str,
        *,
        kanban_run_id: int | None,
        summary: str,
    ) -> bool:
        with self._kanban.connect_closing(board=self.board) as connection:
            return self._kanban.complete_task(
                connection,
                task_id,
                result=summary,
                summary=summary,
                expected_run_id=kanban_run_id,
            )

    def fail_task(
        self,
        task_id: str,
        *,
        kanban_run_id: int | None,
        reason: str,
    ) -> bool:
        with self._kanban.connect_closing(board=self.board) as connection:
            return self._kanban.block_task(
                connection,
                task_id,
                reason=reason,
                kind="capability",
                expected_run_id=kanban_run_id,
            )

    def task_status(self, task_id: str) -> str | None:
        with self._kanban.connect_closing(board=self.board) as connection:
            task = self._kanban.get_task(connection, task_id)
            return task.status if task else None

