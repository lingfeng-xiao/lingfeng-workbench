import Link from "next/link";
import { AppShell } from "./_components/AppShell";
import { EmptyState } from "./_components/EmptyState";
import { ServiceProblem } from "./_components/ServiceProblem";
import { StatusBadge } from "./_components/StatusBadge";
import { formatTimestamp } from "./_lib/presentation";
import {
  listNodes,
  listWorkItems,
  type NodeSummary,
  type WorkItemSummary,
} from "./_lib/workbench-service";
import { requireChatGPTUser } from "./chatgpt-auth";

export const dynamic = "force-dynamic";

const ACTIVE_STATUSES = new Set(["open", "in_progress"]);

export default async function Home() {
  await requireChatGPTUser("/");
  const homeState = await loadHomeState();

  if (homeState.kind === "error") {
    return (
      <AppShell
        currentPath="/"
        eyebrow="CONTROL OVERVIEW"
        title="工作正在什么位置"
        description="只读视图暂时无法取得控制状态。"
      >
        <ServiceProblem error={homeState.error} />
      </AppShell>
    );
  }

  const activeWorkItems = homeState.workItems.filter((item) =>
    ACTIVE_STATUSES.has(item.status),
  );
  const recentTerminalWorkItems = homeState.workItems
    .filter((item) => !ACTIVE_STATUSES.has(item.status))
    .slice(0, 6);
  const onlineNodes = homeState.nodes.filter(
    (node) => node.status === "online",
  ).length;

  return (
    <AppShell
      currentPath="/"
      eyebrow="CONTROL OVERVIEW"
      title="工作正在什么位置"
      description="只展示 Service 保存的短控制状态；完整日志、产物和 Runtime 对话始终留在执行电脑。"
    >
      <section className="metric-grid" aria-label="Workbench 概览">
        <MetricCard
          label="进行中的工作"
          value={activeWorkItems.length}
          detail={`${activeWorkItems.reduce((count, item) => count + item.waitingInteractionCount, 0)} 项等待输入`}
        />
        <MetricCard label="在线节点" value={`${onlineNodes}/${homeState.nodes.length}`} detail="以最后心跳为准" />
        <MetricCard label="最近终态" value={recentTerminalWorkItems.length} detail="当前查询窗口内" />
      </section>

      <div className="content-grid">
        <section className="panel panel--primary" aria-labelledby="active-work-title">
          <PanelHeading id="active-work-title" title="活动工作" detail={`${activeWorkItems.length} 项`} />
          {activeWorkItems.length === 0 ? (
            <EmptyState title="现在没有活动工作" description="新 Mission 创建后会出现在这里。" />
          ) : (
            <WorkItemList workItems={activeWorkItems} />
          )}
        </section>

        <section className="panel" aria-labelledby="recent-terminal-title">
          <PanelHeading
            id="recent-terminal-title"
            title="最近终态"
            detail={`${recentTerminalWorkItems.length} 项`}
          />
          {recentTerminalWorkItems.length === 0 ? (
            <EmptyState title="还没有终态记录" description="完成、失败或取消的工作会在这里汇总。" />
          ) : (
            <WorkItemList workItems={recentTerminalWorkItems} compact />
          )}
        </section>
      </div>
    </AppShell>
  );
}

async function loadHomeState(): Promise<
  | { kind: "loaded"; workItems: WorkItemSummary[]; nodes: NodeSummary[] }
  | { kind: "error"; error: unknown }
> {
  try {
    const [workItems, nodes] = await Promise.all([listWorkItems(), listNodes()]);
    return { kind: "loaded", workItems, nodes };
  } catch (error) {
    return { kind: "error", error };
  }
}

function MetricCard({
  label,
  value,
  detail,
}: {
  label: string;
  value: string | number;
  detail: string;
}) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  );
}

function PanelHeading({ id, title, detail }: { id: string; title: string; detail: string }) {
  return (
    <header className="panel-heading">
      <h2 id={id}>{title}</h2>
      <span>{detail}</span>
    </header>
  );
}

function WorkItemList({
  workItems,
  compact = false,
}: {
  workItems: WorkItemSummary[];
  compact?: boolean;
}) {
  return (
    <ul className={compact ? "work-list work-list--compact" : "work-list"}>
      {workItems.map((workItem) => (
        <li key={workItem.workItemId}>
          <Link href={`/work-items/${encodeURIComponent(workItem.workItemId)}`}>
            <span className="work-list__copy">
              <strong>{workItem.title}</strong>
              <small>
                {workItem.phaseCode ? `${workItem.phaseCode} · ` : ""}
                {workItem.progressSummary ?? "Service 尚未收到进度摘要"}
              </small>
              <small>
                {workItem.waitingInteractionCount > 0
                  ? `${workItem.waitingInteractionCount} 项等待输入 · `
                  : ""}
                最后同步于 {formatTimestamp(workItem.lastSyncedAt ?? workItem.updatedAt)}
              </small>
            </span>
            <StatusBadge status={workItem.status} />
          </Link>
        </li>
      ))}
    </ul>
  );
}
