"""Local-only execution state and evidence locations."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from ..domain import MissionAssignment, utc_now_iso


LOCAL_SCHEMA = """
CREATE TABLE IF NOT EXISTS local_runs (
    run_id TEXT PRIMARY KEY,
    mission_id TEXT NOT NULL,
    runtime_kind TEXT NOT NULL,
    runtime_session_ref TEXT,
    state TEXT NOT NULL,
    run_dir TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


class NodeState:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.runs_dir = self.root / "runs"
        self.logs_dir = self.root / "logs"
        self.runs_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.database_path = self.root / "node.db"
        with self._connect() as connection:
            connection.executescript(LOCAL_SCHEMA)

    def materialize(self, assignment: MissionAssignment) -> Path:
        run_dir = self.runs_dir / assignment.run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        mission_path = run_dir / "mission.json"
        if not mission_path.exists():
            mission_path.write_text(
                json.dumps(
                    assignment.to_payload(),
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )
        now = utc_now_iso()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO local_runs (
                    run_id, mission_id, runtime_kind, state, run_dir,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'assigned', ?, ?, ?)
                """,
                (
                    assignment.run_id,
                    assignment.mission_id,
                    assignment.runtime_kind,
                    str(run_dir),
                    now,
                    now,
                ),
            )
        return run_dir

    def update_run(
        self,
        run_id: str,
        *,
        state: str,
        runtime_session_ref: str | None = None,
    ) -> None:
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE local_runs
                   SET state = ?,
                       runtime_session_ref = COALESCE(?, runtime_session_ref),
                       updated_at = ?
                 WHERE run_id = ?
                """,
                (state, runtime_session_ref, utc_now_iso(), run_id),
            )
            if cursor.rowcount != 1:
                raise LookupError(f"local run not found: {run_id}")

    def snapshot(self) -> list[dict[str, object]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT run_id, mission_id, runtime_kind, runtime_session_ref,
                       state, run_dir, updated_at
                  FROM local_runs ORDER BY created_at DESC LIMIT 20
                """
            ).fetchall()
            return [dict(row) for row in rows]

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=5)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
        return connection
