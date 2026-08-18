"""Office-node mission loop and protocol/runtime translation."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Iterator

from ..domain import InteractionPolicy, MissionAssignment, new_identifier
from ..protocol import Correlation, Envelope, MessageType
from ..runtime.base import RuntimeAdapter, RuntimeEvent, RuntimeEventType
from .config import NodeConfig
from .ssh_stream import SshWorkerClient
from .state import NodeState


class NodeRunner:
    def __init__(
        self,
        config: NodeConfig,
        state: NodeState,
        runtime: RuntimeAdapter,
        client: SshWorkerClient,
    ) -> None:
        self.config = config
        self.state = state
        self.runtime = runtime
        self.client = client
        self.sequence = 0

    def handshake(self) -> None:
        ok, detail = self.runtime.probe()
        if not ok:
            raise RuntimeError(detail)
        response = self._exchange(
            MessageType.HELLO,
            {
                "display_name": self.config.display_name,
                "runtime_capabilities": self.runtime.capabilities().to_payload(),
            },
        )
        self._expect(response, MessageType.WELCOME)

    def run_once(self) -> bool:
        response = self._exchange(MessageType.CLAIM_REQUEST)
        if response.message_type is MessageType.NO_MISSION:
            return False
        self._expect(response, MessageType.MISSION_ASSIGNED)
        assignment = MissionAssignment(
            work_item_id=response.payload["work_item_id"],
            mission_id=response.payload["mission_id"],
            kanban_task_id=response.payload["kanban_task_id"],
            run_id=response.payload["run_id"],
            objective=response.payload["objective"],
            acceptance_summary=response.payload["acceptance_summary"],
            target_node_id=response.payload["target_node_id"],
            local_workspace_ref=response.payload["local_workspace_ref"],
            runtime_kind=response.payload.get("runtime_kind", "ws"),
            interaction_policy=response.payload.get(
                "interaction_policy",
                "ask_when_blocked",
            ),
            authorized_side_effects_summary=response.payload.get(
                "authorized_side_effects_summary",
                "none",
            ),
        )
        workspace = self.config.resolve_workspace(assignment.local_workspace_ref)
        local_run_dir = self.state.materialize(assignment)
        self._execute_assignment(assignment, workspace, local_run_dir)
        return True

    def run_forever(self) -> None:
        self.handshake()
        while True:
            worked = self.run_once()
            if not worked:
                heartbeat = self._exchange(MessageType.HEARTBEAT)
                if heartbeat.message_type not in {
                    MessageType.ACK,
                    MessageType.INTERACTION_RESPONSE,
                }:
                    self._expect(heartbeat, MessageType.ACK)
                time.sleep(self.config.poll_seconds)

    def _execute_assignment(
        self,
        assignment: MissionAssignment,
        workspace: Path,
        local_run_dir: Path,
    ) -> None:
        session_ref: str | None = None
        events = self.runtime.run(
            assignment,
            workspace=workspace,
            local_run_dir=local_run_dir,
        )
        approval_gate_pending = (
            assignment.interaction_policy
            is InteractionPolicy.APPROVE_AFTER_SESSION_START
        )
        run_started_recorded = False
        while True:
            cycle = self._consume_runtime_events(
                events,
                assignment,
                session_ref=session_ref,
                approval_gate_pending=approval_gate_pending,
                run_started_recorded=run_started_recorded,
            )
            session_ref = cycle.session_ref
            run_started_recorded = run_started_recorded or session_ref is not None
            if cycle.terminal:
                return
            if cycle.interaction_response is None or session_ref is None:
                self._report_failed(
                    assignment,
                    "Runtime 请求了交互，但没有可恢复的 Session",
                )
                return
            self.runtime.cancel()
            self._close_event_stream(events)
            if approval_gate_pending:
                approval_gate_pending = False
                if not self._approval_is_granted(cycle.interaction_response):
                    self._report_interrupted(
                        assignment,
                        "用户未批准本次执行，原 WS Session 未继续",
                    )
                    return
                resume_input = (
                    "用户已批准本次执行。现在继续完成本 Session 中记录的原任务；"
                    "遵守原验收摘要和授权边界。"
                )
            else:
                resume_input = cycle.interaction_response
            events = self.runtime.resume(
                runtime_session_ref=session_ref,
                input_text=resume_input,
                workspace=workspace,
                local_run_dir=local_run_dir,
            )

    def _consume_runtime_events(
        self,
        events: Iterator[RuntimeEvent],
        assignment: MissionAssignment,
        *,
        session_ref: str | None,
        approval_gate_pending: bool,
        run_started_recorded: bool,
    ) -> "RuntimeCycleResult":
        correlation = Correlation(
            work_item_id=assignment.work_item_id,
            mission_id=assignment.mission_id,
            run_id=assignment.run_id,
        )
        for event in events:
            if event.runtime_session_ref:
                session_ref = event.runtime_session_ref
            if event.event_type is RuntimeEventType.STARTED:
                self.state.update_run(
                    assignment.run_id,
                    state="running",
                    runtime_session_ref=session_ref,
                )
                if not run_started_recorded:
                    self._expect_ack(
                        self._exchange(
                            MessageType.RUN_STARTED,
                            {"runtime_session_ref": session_ref},
                            correlation,
                        )
                    )
                if approval_gate_pending:
                    response = self._request_start_approval(
                        assignment,
                        correlation,
                    )
                    return RuntimeCycleResult(
                        terminal=False,
                        session_ref=session_ref,
                        interaction_response=response,
                    )
            elif event.event_type is RuntimeEventType.PROGRESS:
                self._expect_ack(
                    self._exchange(
                        MessageType.RUN_EVENT,
                        {
                            "event_type": "PROGRESS",
                            "summary": event.summary or "Runtime 正在执行",
                            **(
                                {"checkpoint_id": event.checkpoint_id}
                                if event.checkpoint_id
                                else {}
                            ),
                        },
                        correlation,
                    )
                )
            elif event.event_type is RuntimeEventType.HEARTBEAT:
                heartbeat = self._exchange(MessageType.HEARTBEAT)
                if heartbeat.message_type is MessageType.INTERACTION_RESPONSE:
                    return RuntimeCycleResult(
                        terminal=False,
                        session_ref=session_ref,
                        interaction_response=heartbeat.payload["response_summary"],
                    )
                self._expect_ack(heartbeat)
            elif event.event_type is RuntimeEventType.INTERACTION_REQUIRED:
                checkpoint_id = event.checkpoint_id or new_identifier("cp")
                payload = {
                    "checkpoint_id": checkpoint_id,
                    "kind": event.payload.get("kind", "clarification"),
                    "prompt_summary": event.summary or "Runtime 需要用户输入",
                    "options": event.payload.get("options", []),
                    **(
                        {"risk_summary": event.payload["risk_summary"]}
                        if event.payload.get("risk_summary")
                        else {}
                    ),
                }
                self._expect_ack(
                    self._exchange(
                        MessageType.INTERACTION_REQUIRED,
                        payload,
                        correlation,
                    )
                )
                self.state.update_run(assignment.run_id, state="waiting_interaction")
                response = self._wait_for_interaction_response(assignment.run_id)
                return RuntimeCycleResult(
                    terminal=False,
                    session_ref=session_ref,
                    interaction_response=response,
                )
            elif event.event_type is RuntimeEventType.COMPLETED:
                self._expect_ack(
                    self._exchange(
                        MessageType.RUN_COMPLETED,
                        {"result_summary": event.summary or "Runtime 已完成"},
                        correlation,
                    )
                )
                self.state.update_run(assignment.run_id, state="completed")
                return RuntimeCycleResult(True, session_ref, None)
            elif event.event_type in {
                RuntimeEventType.FAILED,
                RuntimeEventType.INTERRUPTED,
            }:
                message_type = (
                    MessageType.RUN_INTERRUPTED
                    if event.event_type is RuntimeEventType.INTERRUPTED
                    else MessageType.RUN_FAILED
                )
                self._expect_ack(
                    self._exchange(
                        message_type,
                        {"reason": event.summary or "Runtime 执行失败"},
                        correlation,
                    )
                )
                self.state.update_run(
                    assignment.run_id,
                    state=event.event_type.value,
                )
                return RuntimeCycleResult(True, session_ref, None)
        self._report_failed(assignment, "Runtime 事件流结束但没有终态")
        return RuntimeCycleResult(True, session_ref, None)

    def _request_start_approval(
        self,
        assignment: MissionAssignment,
        correlation: Correlation,
    ) -> str:
        checkpoint_id = new_identifier("cp")
        prompt = (
            "WS Session 已建立，是否批准继续执行此 Mission？"
            f"授权范围：{assignment.authorized_side_effects_summary}"
        )
        self._expect_ack(
            self._exchange(
                MessageType.INTERACTION_REQUIRED,
                {
                    "checkpoint_id": checkpoint_id,
                    "kind": "approval",
                    "prompt_summary": prompt,
                    "options": ["批准本次", "拒绝"],
                    "risk_summary": assignment.authorized_side_effects_summary,
                },
                correlation,
            )
        )
        self.state.update_run(assignment.run_id, state="waiting_approval")
        return self._wait_for_interaction_response(assignment.run_id)

    def _wait_for_interaction_response(self, run_id: str) -> str:
        while True:
            response = self._exchange(MessageType.HEARTBEAT)
            if response.message_type is MessageType.INTERACTION_RESPONSE:
                if response.correlation.run_id != run_id:
                    raise RuntimeError("received interaction response for another run")
                return str(response.payload["response_summary"])
            self._expect_ack(response)
            time.sleep(min(self.config.poll_seconds, 30))

    def _report_failed(
        self,
        assignment: MissionAssignment,
        reason: str,
    ) -> None:
        correlation = Correlation(
            work_item_id=assignment.work_item_id,
            mission_id=assignment.mission_id,
            run_id=assignment.run_id,
        )
        self._expect_ack(
            self._exchange(
                MessageType.RUN_FAILED,
                {"reason": reason},
                correlation,
            )
        )
        self.state.update_run(assignment.run_id, state="failed")

    def _report_interrupted(
        self,
        assignment: MissionAssignment,
        reason: str,
    ) -> None:
        correlation = Correlation(
            work_item_id=assignment.work_item_id,
            mission_id=assignment.mission_id,
            run_id=assignment.run_id,
        )
        self._expect_ack(
            self._exchange(
                MessageType.RUN_INTERRUPTED,
                {"reason": reason},
                correlation,
            )
        )
        self.state.update_run(assignment.run_id, state="interrupted")

    @staticmethod
    def _approval_is_granted(response: str) -> bool:
        normalized = " ".join(response.strip().lower().split())
        accepted = {"批准", "批准本次", "同意", "yes", "approve", "approved", "ok"}
        return normalized in accepted or normalized.startswith("批准")

    @staticmethod
    def _close_event_stream(events: Iterator[RuntimeEvent]) -> None:
        close = getattr(events, "close", None)
        if callable(close):
            close()

    def _exchange(
        self,
        message_type: MessageType,
        payload: dict[str, object] | None = None,
        correlation: Correlation | None = None,
    ) -> Envelope:
        request = Envelope(
            message_type=message_type,
            node_id=self.config.node_id,
            payload=payload or {},
            correlation=correlation or Correlation(),
            sequence=self.sequence,
        )
        self.sequence += 1
        response = self.client.exchange(request)
        if response.message_type is MessageType.ERROR:
            raise RuntimeError(response.payload.get("message", "worker rejected request"))
        return response

    @staticmethod
    def _expect(response: Envelope, message_type: MessageType) -> None:
        if response.message_type is not message_type:
            raise RuntimeError(
                f"expected {message_type.value}, got {response.message_type.value}"
            )

    def _expect_ack(self, response: Envelope) -> None:
        self._expect(response, MessageType.ACK)


class RuntimeCycleResult:
    def __init__(
        self,
        terminal: bool,
        session_ref: str | None,
        interaction_response: str | None,
    ) -> None:
        self.terminal = terminal
        self.session_ref = session_ref
        self.interaction_response = interaction_response
