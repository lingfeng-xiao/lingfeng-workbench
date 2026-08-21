import { AppShell } from "../_components/AppShell";
import { EmptyState } from "../_components/EmptyState";
import { listTasks } from "../_lib/task-service";
import type { TaskSummary } from "../_lib/task-contracts";
import { requireChatGPTUser } from "../chatgpt-auth";
import { TaskList } from "../page";

export const dynamic = "force-dynamic";

export default async function AttentionPage() {
  await requireChatGPTUser("/attention");
  const state = await loadAttentionTasks();
  if (state.kind === "error") {
    return <AppShell currentPath="/attention" eyebrow="ATTENTION" title="关注队列暂不可用" description="无法取得严格校验后的 Task 投影。"><section className="problem-panel" role="alert"><p>SERVICE UNAVAILABLE</p><h2>暂时无法聚合关注项</h2><span>请稍后刷新。</span></section></AppShell>;
  }
  return <AppShell currentPath="/attention" eyebrow="ATTENTION" title="需要人确认的 Task" description="聚合 REVIEW/PENDING、执行失败或不确定、Node 离线与 stale；Run 终态不会替代人工验收。"><section className="panel panel--primary"><header className="panel-heading"><h2>关注队列</h2><span>{state.tasks.length} 项</span></header>{state.tasks.length ? <TaskList tasks={state.tasks} /> : <EmptyState title="没有需关注项" description="当前没有待验收、失败、不确定、离线或 stale 的 Task。" />}</section></AppShell>;
}

async function loadAttentionTasks(): Promise<{ kind: "loaded"; tasks: TaskSummary[] } | { kind: "error" }> {
  try {
    const tasks = (await listTasks("?includeArchived=true&limit=100")).filter((task) => task.businessStatus === "REVIEW" || task.attentionState !== "NONE" || task.nodeStatus === "offline" || task.stale);
    return { kind: "loaded", tasks };
  } catch {
    return { kind: "error" };
  }
}
