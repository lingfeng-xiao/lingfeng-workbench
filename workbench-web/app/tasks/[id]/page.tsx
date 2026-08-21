import type { Metadata } from "next";
import { AppShell } from "../../_components/AppShell";
import { TaskDetailClient } from "../../_components/TaskDetailClient";
import { formatTimestamp } from "../../_lib/presentation";
import type { TaskDetail } from "../../_lib/task-contracts";
import { getTask } from "../../_lib/task-service";
import { requireChatGPTUser } from "../../chatgpt-auth";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Task 详情" };

export default async function TaskPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  await requireChatGPTUser(`/tasks/${encodeURIComponent(id)}`);
  const state = await loadTask(id);
  if (state.kind === "error") {
    return <AppShell currentPath="" eyebrow="TASK" title="无法读取 Task" description="Task 不存在、凭证无权限，或 Service 响应未通过严格合同校验。"><section className="problem-panel" role="alert"><p>DETAIL UNAVAILABLE</p><h2>没有可安全展示的状态</h2><span>请返回 Task 池重试；页面不会显示未经合同校验的数据。</span></section></AppShell>;
  }
  const task = state.task;
  return <AppShell currentPath="" eyebrow={`TASK · ${task.taskId}`} title={task.title} description={`优先级 ${task.priority} · 更新于 ${formatTimestamp(task.updatedAt)} · ${task.attentionState}`}><TaskDetailClient initialTask={task} /></AppShell>;
}

async function loadTask(taskId: string): Promise<{ kind: "loaded"; task: TaskDetail } | { kind: "error" }> {
  try {
    return { kind: "loaded", task: await getTask(taskId) };
  } catch {
    return { kind: "error" };
  }
}
