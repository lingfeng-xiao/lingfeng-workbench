"""Long-lived SSH stdio transport for the office node."""

from __future__ import annotations

import subprocess
import threading
from pathlib import Path
from typing import TextIO

from ..protocol import Envelope
from .config import NodeConfig


class SshWorkerClient:
    def __init__(self, config: NodeConfig) -> None:
        self.config = config
        self.process: subprocess.Popen[str] | None = None
        self._stderr_thread: threading.Thread | None = None

    def connect(self) -> None:
        if self.process is not None:
            raise RuntimeError("SSH worker stream is already connected")
        command = [
            "ssh",
            "-T",
            "-i",
            str(self.config.ssh_key_path),
            "-p",
            str(self.config.ssh_port),
            "-o",
            "BatchMode=yes",
            "-o",
            "ServerAliveInterval=30",
            "-o",
            "ServerAliveCountMax=3",
            f"{self.config.ssh_user}@{self.config.ssh_host}",
            self.config.remote_command,
        ]
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        self.process = process
        assert process.stderr is not None
        log_path = self.config.state_dir / "logs" / "ssh-stderr.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr,
            args=(process.stderr, log_path),
            daemon=True,
        )
        self._stderr_thread.start()

    def exchange(self, request: Envelope) -> Envelope:
        process = self._require_process()
        assert process.stdin is not None
        assert process.stdout is not None
        process.stdin.write(request.to_json() + "\n")
        process.stdin.flush()
        raw = process.stdout.readline()
        if not raw:
            code = process.poll()
            raise ConnectionError(f"SSH worker stream closed unexpectedly (exit={code})")
        response = Envelope.from_json(raw.rstrip("\r\n"))
        request_id = response.payload.get("request_message_id")
        if request_id != request.message_id:
            raise ConnectionError("worker response correlation does not match request")
        return response

    def close(self) -> None:
        process = self.process
        self.process = None
        if process is None:
            return
        if process.stdin is not None:
            process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()

    def _require_process(self) -> subprocess.Popen[str]:
        if self.process is None:
            raise RuntimeError("SSH worker stream is not connected")
        return self.process

    @staticmethod
    def _drain_stderr(stream: TextIO, path: Path) -> None:
        with path.open("a", encoding="utf-8") as destination:
            for line in stream:
                destination.write(line)
                destination.flush()

    def __enter__(self) -> "SshWorkerClient":
        self.connect()
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()
