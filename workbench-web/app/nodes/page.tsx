import type { Metadata } from "next";
import { AppShell } from "../_components/AppShell";
import { EmptyState } from "../_components/EmptyState";
import { ServiceProblem } from "../_components/ServiceProblem";
import { StatusBadge } from "../_components/StatusBadge";
import { formatTimestamp } from "../_lib/presentation";
import { listNodes, type NodeSummary } from "../_lib/workbench-service";
import { requireChatGPTUser } from "../chatgpt-auth";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "执行节点",
};

export default async function NodesPage() {
  await requireChatGPTUser("/nodes");
  const nodeState = await loadNodeState();

  if (nodeState.kind === "error") {
    return (
      <AppShell
        currentPath="/nodes"
        eyebrow="EXECUTION NODES"
        title="电脑各自独立"
        description="无法取得 Node 状态投影。"
      >
        <ServiceProblem error={nodeState.error} />
      </AppShell>
    );
  }

  return (
    <AppShell
      currentPath="/nodes"
      eyebrow="EXECUTION NODES"
      title="电脑各自独立"
      description="这里只显示 Service 的 Node 能力和心跳投影，不读取执行电脑的本地上下文。"
    >
      {nodeState.nodes.length === 0 ? (
        <section className="panel">
          <EmptyState title="还没有注册 Node" description="Node 完成 hello 后会出现在这里。" />
        </section>
      ) : (
        <section className="node-grid" aria-label="执行节点">
          {nodeState.nodes.map((node) => (
            <article className="node-card" key={node.nodeId}>
              <header>
                <span className="node-monogram" aria-hidden="true">
                  {node.displayName.slice(0, 2).toUpperCase()}
                </span>
                <div><h2>{node.displayName}</h2><p>{node.nodeId}</p></div>
                <StatusBadge status={node.status} />
              </header>
              <div className="node-heartbeat">
                <span>最后心跳</span>
                <strong>{formatTimestamp(node.lastHeartbeatAt)}</strong>
              </div>
              <dl className="node-projection">
                <div>
                  <dt>当前 Run</dt>
                  <dd>{node.currentRunId ?? "无活动 Run"}</dd>
                </div>
                <div>
                  <dt>最后同步</dt>
                  <dd>{formatTimestamp(node.lastSyncedAt)}</dd>
                </div>
              </dl>
              <div className="capability-list" aria-label="能力">
                {node.capabilities.length === 0 ? (
                  <span className="capability capability--empty">未上报能力</span>
                ) : (
                  node.capabilities.map((capability) => (
                    <span className="capability" key={capability}>{capability}</span>
                  ))
                )}
              </div>
            </article>
          ))}
        </section>
      )}
    </AppShell>
  );
}

async function loadNodeState(): Promise<
  | { kind: "loaded"; nodes: NodeSummary[] }
  | { kind: "error"; error: unknown }
> {
  try {
    return { kind: "loaded", nodes: await listNodes() };
  } catch (error) {
    return { kind: "error", error };
  }
}
