"""WS CLI adapter using its stable JSON event stream."""

from __future__ import annotations

import json
import logging
import queue
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Iterator, TextIO

from ..domain import (
    InteractionPolicy,
    MissionAssignment,
    RuntimeCapabilities,
    compact_summary,
)
from .base import RuntimeEvent, RuntimeEventType


logger = logging.getLogger(__name__)


class WsCliAdapter:
    """Execute one WS session without assuming its model or agent topology."""

    def __init__(
        self,
        executable: str = "ws",
        *,
        heartbeat_seconds: float = 15.0,
        progress_seconds: float = 20.0,
    ) -> None:
        self.executable = executable
        self.heartbeat_seconds = heartbeat_seconds
        self.progress_seconds = progress_seconds
        self._process: subprocess.Popen[str] | None = None

    def probe(self) -> tuple[bool, str]:
        resolved = shutil.which(self.executable)
        if resolved is None:
            return False, f"WS executable not found: {self.executable}"
        try:
            completed = subprocess.run(
                [resolved, "--version"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=15,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            return False, f"WS probe failed: {exc}"
        version = (completed.stdout or completed.stderr).strip().splitlines()
        detail = version[0] if version else f"exit={completed.returncode}"
        return completed.returncode == 0, detail

    def capabilities(self) -> RuntimeCapabilities:
        return RuntimeCapabilities(
            runtime_kind="ws",
            persistent_session=True,
            structured_events=True,
            interactive_input=True,
            cancel=True,
            resume=True,
            subagents=True,
            agent_teams=True,
            metadata={"transport": "ws-run-json"},
        )

    def run(
        self,
        assignment: MissionAssignment,
        *,
        workspace: Path,
        local_run_dir: Path,
    ) -> Iterator[RuntimeEvent]:
        prompt = self._initial_prompt(assignment)
        command = [
            self._resolved_executable(),
            "run",
            "--format",
            "json",
            "--dir",
            str(workspace),
            prompt,
        ]
        yield from self._execute(command, local_run_dir=local_run_dir)

    def resume(
        self,
        *,
        runtime_session_ref: str,
        input_text: str,
        workspace: Path,
        local_run_dir: Path,
    ) -> Iterator[RuntimeEvent]:
        command = [
            self._resolved_executable(),
            "run",
            "--format",
            "json",
            "--session",
            runtime_session_ref,
            "--dir",
            str(workspace),
            input_text,
        ]
        yield from self._execute(command, local_run_dir=local_run_dir)

    def cancel(self) -> None:
        process = self._process
        if process is not None and process.poll() is None:
            process.terminate()

    def _resolved_executable(self) -> str:
        return shutil.which(self.executable) or self.executable

    @staticmethod
    def _initial_prompt(assignment: MissionAssignment) -> str:
        task_contract = (
            f"任务目标：{assignment.objective}\n\n"
            f"验收摘要：{assignment.acceptance_summary}\n\n"
            "完整上下文只从当前本地工作区读取。执行过程中保留本地证据；"
            "最终给出简短、可上传到个人 Hermes 的结果摘要。"
        )
        if (
            assignment.interaction_policy
            is InteractionPolicy.APPROVE_AFTER_SESSION_START
        ):
            return (
                "这是审批前的 Session 建立步骤。不要调用工具，不要执行下面的任务，"
                "只回复 LINGFENG_APPROVAL_SESSION_READY。审批通过后，我会在同一个 "
                f"Session 中通知你继续。\n\n{task_contract}"
            )
        return task_contract

    def _execute(
        self,
        command: list[str],
        *,
        local_run_dir: Path,
    ) -> Iterator[RuntimeEvent]:
        local_run_dir.mkdir(parents=True, exist_ok=True)
        event_path = local_run_dir / "runtime-events.ndjson"
        result_path = local_run_dir / "result.md"
        stderr_path = local_run_dir / "runtime-stderr.log"
        events: queue.Queue[tuple[str, str | None]] = queue.Queue()
        full_text: list[str] = []
        session_id: str | None = None
        terminal_reason: str | None = None
        parse_error: str | None = None
        last_progress_at = 0.0

        try:
            process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        except OSError as exc:
            yield RuntimeEvent(
                RuntimeEventType.FAILED,
                summary=f"WS 启动失败：{exc}",
            )
            return
        self._process = process
        assert process.stdout is not None
        assert process.stderr is not None

        stdout_thread = threading.Thread(
            target=self._read_stdout,
            args=(process.stdout, events),
            daemon=True,
        )
        stderr_thread = threading.Thread(
            target=self._drain_stderr,
            args=(process.stderr, stderr_path),
            daemon=True,
        )
        stdout_thread.start()
        stderr_thread.start()

        with event_path.open("a", encoding="utf-8") as event_file:
            stdout_done = False
            while not stdout_done:
                try:
                    source, line = events.get(timeout=self.heartbeat_seconds)
                except queue.Empty:
                    yield RuntimeEvent(RuntimeEventType.HEARTBEAT)
                    continue
                if source == "eof":
                    stdout_done = True
                    continue
                assert line is not None
                event_file.write(line + "\n")
                event_file.flush()
                try:
                    decoded = json.loads(line)
                except json.JSONDecodeError as exc:
                    parse_error = f"WS 输出了非法 JSON：{exc.msg}"
                    continue
                if not isinstance(decoded, dict):
                    parse_error = "WS JSON 事件根节点不是对象"
                    continue
                discovered_session = decoded.get("sessionID")
                if discovered_session and session_id is None:
                    session_id = str(discovered_session)
                    yield RuntimeEvent(
                        RuntimeEventType.STARTED,
                        runtime_session_ref=session_id,
                    )
                event_type = str(decoded.get("type") or "").lower()
                if event_type in {
                    "permission",
                    "permission_asked",
                    "permission.requested",
                    "approval_required",
                }:
                    permission = decoded.get("permission")
                    detail = permission if isinstance(permission, dict) else decoded
                    prompt = str(
                        detail.get("description")
                        or detail.get("title")
                        or "WS 请求执行一次需要批准的操作"
                    )
                    yield RuntimeEvent(
                        RuntimeEventType.INTERACTION_REQUIRED,
                        summary=self._progress_summary(prompt),
                        runtime_session_ref=session_id,
                        payload={
                            "kind": "approval",
                            "options": ["批准本次", "拒绝"],
                            "risk_summary": self._progress_summary(prompt),
                        },
                    )
                    continue
                part = decoded.get("part")
                if not isinstance(part, dict):
                    continue
                part_type = part.get("type")
                if part_type == "text" and part.get("text"):
                    text = str(part["text"])
                    full_text.append(text)
                    now = time.monotonic()
                    if now - last_progress_at >= self.progress_seconds:
                        last_progress_at = now
                        yield RuntimeEvent(
                            RuntimeEventType.PROGRESS,
                            summary=self._progress_summary(text),
                        )
                elif part_type == "step-finish":
                    terminal_reason = str(part.get("reason") or "unknown")

        return_code = process.wait()
        stderr_thread.join(timeout=2)
        self._process = None
        result_path.write_text("".join(full_text), encoding="utf-8")
        if return_code != 0:
            yield RuntimeEvent(
                RuntimeEventType.FAILED,
                summary=f"WS 进程异常退出（exit={return_code}），详情保存在本机日志",
                runtime_session_ref=session_id,
            )
        elif parse_error:
            yield RuntimeEvent(
                RuntimeEventType.FAILED,
                summary=parse_error,
                runtime_session_ref=session_id,
            )
        elif terminal_reason != "stop":
            yield RuntimeEvent(
                RuntimeEventType.FAILED,
                summary=f"WS 未返回正常终态（reason={terminal_reason or 'missing'}）",
                runtime_session_ref=session_id,
            )
        else:
            result = "".join(full_text).strip()
            summary = self._result_summary(result)
            yield RuntimeEvent(
                RuntimeEventType.COMPLETED,
                summary=summary,
                runtime_session_ref=session_id,
                payload={"local_result": str(result_path)},
            )

    @staticmethod
    def _read_stdout(
        stream: TextIO,
        output: queue.Queue[tuple[str, str | None]],
    ) -> None:
        try:
            for line in stream:
                output.put(("stdout", line.rstrip("\r\n")))
        finally:
            output.put(("eof", None))

    @staticmethod
    def _drain_stderr(stream: TextIO, path: Path) -> None:
        with path.open("a", encoding="utf-8") as destination:
            for line in stream:
                destination.write(line)
                destination.flush()

    @staticmethod
    def _progress_summary(text: str) -> str:
        compact = " ".join(text.split())
        if len(compact) > 300:
            compact = compact[:297] + "..."
        return compact_summary(compact or "WS 正在执行", field_name="progress")

    @staticmethod
    def _result_summary(text: str) -> str:
        compact = " ".join(text.split())
        if not compact:
            return "WS 已完成，完整结果保存在办公电脑"
        if len(compact) > 700:
            compact = compact[:697] + "..."
        return compact_summary(compact, field_name="result")
