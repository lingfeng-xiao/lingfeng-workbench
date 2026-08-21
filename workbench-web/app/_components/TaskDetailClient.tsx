"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { formatTimestamp } from "../_lib/presentation";
import { parseTaskDetail, type TaskAction, type TaskDetail } from "../_lib/task-contracts";
import { StatusBadge } from "./StatusBadge";
import { TaskForm } from "./TaskForm";

const ACTION_PATHS: Partial<Record<TaskAction, string>> = {
  MARK_READY: "mark-ready",
  START: "start",
  ACCEPT: "accept",
  REQUEST_CHANGES: "request-changes",
  CANCEL: "cancel",
  ARCHIVE: "archive",
  RESTORE: "restore",
};
const ACTION_LABELS: Partial<Record<TaskAction, string>> = {
  MARK_READY: "标记为 READY",
  START: "开始执行",
  REQUEST_CHANGES: "退回修改",
  CANCEL: "取消 Task",
  ARCHIVE: "归档",
  RESTORE: "恢复",
};

export function TaskDetailClient({ initialTask }: { initialTask: TaskDetail }) {
  const [task, setTask] = useState(initialTask);
  const [problem, setProblem] = useState<string | null>(null);
  const [workingAction, setWorkingAction] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const etag = useRef(`"${initialTask.version}"`);
  const actionKeys = useRef(new Map<string, string>());

  useEffect(() => {
    if (task.businessStatus !== "IN_PROGRESS") return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const response = await fetch(`/api/tasks/${encodeURIComponent(task.taskId)}`, {
          headers: { "if-none-match": etag.current },
          cache: "no-store",
        });
        if (response.status !== 304) {
          if (!response.ok) throw new Error("无法刷新 Task 状态");
          const nextTask = parseTaskDetail(await response.json());
          if (!cancelled) setTask(nextTask);
          etag.current = response.headers.get("etag") ?? `"${nextTask.version}"`;
        }
      } catch {
        if (!cancelled) setProblem("条件刷新暂时失败；当前页面保留最后一次短状态。");
      }
      if (!cancelled) timer = setTimeout(poll, document.hidden ? 15_000 : 4_000);
    };
    timer = setTimeout(poll, document.hidden ? 15_000 : 4_000);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [task.businessStatus, task.taskId]);

  async function submitAction(action: TaskAction, form: FormData): Promise<void> {
    const actionPath = ACTION_PATHS[action];
    if (!actionPath) return;
    setProblem(null);
    setWorkingAction(action);
    const existingKey = actionKeys.current.get(action);
    const key = existingKey ?? `web_${crypto.randomUUID()}`;
    actionKeys.current.set(action, key);
    try {
      const payload: Record<string, unknown> = {
        expectedVersion: task.version,
        reason: String(form.get("reason") ?? "").trim(),
      };
      if (!payload.reason) throw new Error("操作原因不能为空");
      if (action === "ACCEPT") {
        payload.deliverySummary = String(form.get("deliverySummary") ?? "").trim();
        payload.commitSha = String(form.get("commitSha") ?? "").trim();
        payload.prUrl = String(form.get("prUrl") ?? "").trim();
      }
      const response = await fetch(`/api/tasks/${encodeURIComponent(task.taskId)}/${actionPath}`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": key,
          "x-workbench-csrf": "1",
        },
        body: JSON.stringify(payload),
      });
      const responsePayload = await response.json() as { message?: string };
      if (!response.ok) throw new Error(responsePayload.message ?? "Task 操作失败");
      actionKeys.current.delete(action);
      await refreshTask();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "Task 操作失败");
    } finally {
      setWorkingAction(null);
    }
  }

  async function refreshTask(): Promise<void> {
    const response = await fetch(`/api/tasks/${encodeURIComponent(task.taskId)}`, { cache: "no-store" });
    if (!response.ok) throw new Error("Task 已更新，但刷新详情失败");
    const nextTask = parseTaskDetail(await response.json());
    etag.current = response.headers.get("etag") ?? `"${nextTask.version}"`;
    setTask(nextTask);
  }

  const regularActions = task.allowedActions.filter((action) => action in ACTION_LABELS);
  return (
    <div className="task-detail-stack">
      <section className="axis-grid" aria-label="业务、执行与验收三轴">
        <Axis label="业务状态" detail="Task lifecycle"><StatusBadge status={task.businessStatus} /></Axis>
        <Axis label="执行状态" detail={`${task.runs.length} 次独立 Run`}>
          <StatusBadge status={task.runs.at(-1)?.status ?? "not_started"} />
        </Axis>
        <Axis label="验收状态" detail="Acceptance 独立确认"><StatusBadge status={task.acceptanceStatus} /></Axis>
      </section>

      {problem ? <p className="form-problem" role="alert">{problem}</p> : null}

      <section className="task-control panel">
        <header className="panel-heading"><h2>Task 控制</h2><span>VERSION {task.version}</span></header>
        <dl className="task-contract">
          <ContractItem label="目标" value={task.objective} />
          <ContractItem label="验收摘要" value={task.acceptanceSummary} />
          <ContractItem label="允许副作用" value={task.sideEffectSummary} />
          <ContractItem label="本地解析别名" value={`${task.workspaceRef} · ${task.contextRefs.map(({ ref }) => ref).join(" / ")}`} />
          <ContractItem label="目标 Node" value={`${task.targetNodeId} · ${task.nodeStatus === "online" ? "在线" : "离线"}`} />
          <ContractItem label="最后心跳" value={task.nodeLastHeartbeatAt ? formatTimestamp(task.nodeLastHeartbeatAt) : "尚未观测"} />
        </dl>
        <ActionControls
          task={task}
          actions={regularActions}
          workingAction={workingAction}
          onAction={submitAction}
          onEdit={() => setEditing((value) => !value)}
          editing={editing}
        />
      </section>

      {editing ? <TaskForm task={task} /> : null}
      {task.allowedActions.includes("ACCEPT") ? <AcceptanceForm task={task} working={workingAction === "ACCEPT"} onSubmit={submitAction} /> : null}

      <section className="panel">
        <header className="panel-heading"><h2>执行历史</h2><span>{task.runs.length} RUNS</span></header>
        {task.runs.length === 0 ? <p className="panel-note">尚未开始执行。创建和编辑 Task 不会启动 WS。</p> : (
          <ol className="task-run-list">
            {task.runs.map((run) => (
              <li key={run.runId}>
                <header><strong>Run {run.missionRevision} · REV {run.missionRevision}</strong><StatusBadge status={run.status} /></header>
                <p>{run.progressSummary ?? run.resultSummary ?? "Service 尚未收到短进度"}</p>
                <small>
                  {run.phaseCode ?? "阶段未上报"} · lastObservedAt {run.lastObservedAt ? formatTimestamp(run.lastObservedAt) : "尚未观测"}
                  {run.stale ? " · STALE" : ""}
                </small>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="panel">
        <header className="panel-heading"><h2>Timeline</h2><span>APPEND-ONLY · {task.timeline.length}</span></header>
        <ol className="task-timeline">
          {task.timeline.map((event) => (
            <li key={event.eventId}>
              <span className="timeline-dot" aria-hidden="true" />
              <div><strong>{event.eventType}</strong><p>{event.summary}</p><small>{event.source} · {event.actor} · {formatTimestamp(event.occurredAt)}</small></div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

function Axis({ label, detail, children }: { label: string; detail: string; children: React.ReactNode }) {
  return <article><span>{label}</span>{children}<small>{detail}</small></article>;
}

function ContractItem({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}

function ActionControls({ task, actions, workingAction, onAction, onEdit, editing }: {
  task: TaskDetail;
  actions: TaskAction[];
  workingAction: string | null;
  onAction: (action: TaskAction, form: FormData) => Promise<void>;
  onEdit: () => void;
  editing: boolean;
}) {
  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const action = (event.nativeEvent as SubmitEvent).submitter?.getAttribute("data-action") as TaskAction | null;
    if (action) void onAction(action, new FormData(event.currentTarget));
  }
  return (
    <form className="task-actions" onSubmit={submit}>
      <label className="field"><span>操作原因</span><input name="reason" required maxLength={800} defaultValue="推进 Task 业务闭环" /></label>
      <div className="button-row">
        {task.allowedActions.includes("EDIT") ? <button className="button" type="button" onClick={onEdit}>{editing ? "收起编辑" : "编辑 Task"}</button> : null}
        {actions.map((action) => <button className={action === "START" ? "button button--primary" : "button"} type="submit" data-action={action} disabled={workingAction !== null} key={action}>{workingAction === action ? "处理中…" : ACTION_LABELS[action]}</button>)}
      </div>
    </form>
  );
}

function AcceptanceForm({ task, working, onSubmit }: { task: TaskDetail; working: boolean; onSubmit: (action: TaskAction, form: FormData) => Promise<void> }) {
  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    void onSubmit("ACCEPT", new FormData(event.currentTarget));
  }
  return (
    <form className="task-form panel acceptance-form" onSubmit={submit}>
      <header className="panel-heading"><h2>人工验收</h2><span>REVIEW / PENDING</span></header>
      <div className="form-grid">
        <label className="field field--wide"><span>Delivery summary</span><textarea name="deliverySummary" required maxLength={800} rows={3} defaultValue={task.deliverySummary ?? ""} /></label>
        <label className="field"><span>Commit SHA</span><input name="commitSha" required pattern="[a-fA-F0-9]{7,64}" defaultValue={task.commitSha ?? ""} /></label>
        <label className="field"><span>PR URL（HTTPS）</span><input name="prUrl" required type="url" pattern="https://.*" defaultValue={task.prUrl ?? ""} /></label>
        <label className="field field--wide"><span>验收原因</span><input name="reason" required maxLength={800} defaultValue="已核对交付摘要、commit 和 PR" /></label>
      </div>
      <div className="form-actions"><button className="button button--primary" disabled={working}>{working ? "正在验收…" : "验收并标记 DONE"}</button></div>
    </form>
  );
}
