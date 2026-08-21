import Link from "next/link";
import { AppShell } from "./_components/AppShell";
import { EmptyState } from "./_components/EmptyState";
import { StatusBadge } from "./_components/StatusBadge";
import { formatTimestamp } from "./_lib/presentation";
import type { TaskSummary } from "./_lib/task-contracts";
import { listTasks, TaskServiceError } from "./_lib/task-service";
import { requireChatGPTUser } from "./chatgpt-auth";

export const dynamic = "force-dynamic";

export default async function Home() {
  await requireChatGPTUser("/");
  const state = await loadTasks();
  if (state.kind === "error") {
    return <AppShell currentPath="/" eyebrow="TASK POOL" title="以 Task 为中心的工作池" description="Task Service 暂时不可用。"><TaskProblem error={state.error} /></AppShell>;
  }
  const attentionCount = state.tasks.filter((task) => task.attentionState !== "NONE" || task.businessStatus === "REVIEW").length;
  const executingCount = state.tasks.filter((task) => task.businessStatus === "IN_PROGRESS").length;
  const readyCount = state.tasks.filter((task) => task.businessStatus === "READY").length;
  return (
    <AppShell currentPath="/" eyebrow="TASK POOL" title="以 Task 为中心的工作池" description="创建与编辑只形成 READY 合同；只有显式开始才会创建执行对象并交给 Node。">
      <div className="hero-actions"><Link className="button button--primary" href="/tasks/new">新建 Task</Link><Link className="button" href="/attention">查看需关注项</Link></div>
      <section className="metric-grid" aria-label="Task 概览">
        <Metric label="READY" value={readyCount} detail="等待用户显式开始" />
        <Metric label="执行中" value={executingCount} detail="4 秒条件刷新" />
        <Metric label="需关注" value={attentionCount} detail="验收、失败、离线或 stale" />
      </section>
      <section className="panel panel--primary">
        <header className="panel-heading"><h2>活动 Task</h2><span>{state.tasks.length} 项</span></header>
        {state.tasks.length === 0 ? <EmptyState title="现在没有 Task" description="先创建一个草稿；保存不会启动 WS。" /> : <TaskList tasks={state.tasks} />}
      </section>
    </AppShell>
  );
}

async function loadTasks(): Promise<{ kind: "loaded"; tasks: TaskSummary[] } | { kind: "error"; error: unknown }> {
  try { return { kind: "loaded", tasks: await listTasks("?limit=100") }; }
  catch (error) { return { kind: "error", error }; }
}

export function TaskList({ tasks }: { tasks: TaskSummary[] }) {
  return <ul className="work-list">{tasks.map((task) => (
    <li key={task.taskId}><Link href={`/tasks/${encodeURIComponent(task.taskId)}`}>
      <span className="work-list__copy"><strong>{task.title}</strong><small>{task.progressSummary ?? "尚未产生执行进度"}</small><small>{task.targetNodeId} · lastObservedAt {task.lastObservedAt ? formatTimestamp(task.lastObservedAt) : "尚未观测"}{task.stale ? " · STALE" : ""}</small></span>
      <span className="task-list__statuses"><StatusBadge status={task.businessStatus} />{task.attentionState !== "NONE" ? <StatusBadge status={task.attentionState} /> : null}</span>
    </Link></li>
  ))}</ul>;
}

function Metric({ label, value, detail }: { label: string; value: number; detail: string }) { return <article className="metric-card"><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>; }
function TaskProblem({ error }: { error: unknown }) {
  const contractMismatch = error instanceof TaskServiceError && error.kind === "invalid_response";
  return <section className="problem-panel" role="alert"><p>{contractMismatch ? "CONTRACT MISMATCH" : "SERVICE UNAVAILABLE"}</p><h2>{contractMismatch ? "Service 响应不符合合同" : "暂时无法读取 Task"}</h2><span>页面不会使用缓存旧状态，也不会绕过同源 BFF 或直接连接 Node。</span></section>;
}
