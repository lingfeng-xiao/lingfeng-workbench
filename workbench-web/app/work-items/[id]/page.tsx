import type { Metadata } from "next";
import { AppShell } from "../../_components/AppShell";
import { EmptyState } from "../../_components/EmptyState";
import { ServiceProblem } from "../../_components/ServiceProblem";
import { StatusBadge } from "../../_components/StatusBadge";
import { formatTimestamp } from "../../_lib/presentation";
import {
  getWorkItem,
  type MissionDetail,
  type WorkItemDetail,
} from "../../_lib/workbench-service";
import { requireChatGPTUser } from "../../chatgpt-auth";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "工作详情",
};

export default async function WorkItemPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <AuthenticatedWorkItem workItemId={id} />;
}

async function AuthenticatedWorkItem({ workItemId }: { workItemId: string }) {
  await requireChatGPTUser(`/work-items/${encodeURIComponent(workItemId)}`);
  const detailState = await loadWorkItemState(workItemId);

  if (detailState.kind === "error") {
    return (
      <AppShell
        currentPath=""
        eyebrow="WORK ITEM"
        title="工作详情"
        description="无法取得这项工作的短控制状态。"
      >
        <ServiceProblem error={detailState.error} />
      </AppShell>
    );
  }

  const { workItem } = detailState;
  return (
    <AppShell
      currentPath=""
      eyebrow={`WORK ITEM · ${workItem.workItemId}`}
      title={workItem.title}
      description={`最后更新 ${formatTimestamp(workItem.updatedAt)} · 优先级 ${workItem.priority}`}
    >
      <section className="detail-summary" aria-label="工作摘要">
        <div><span>当前状态</span><StatusBadge status={workItem.status} /></div>
        <div><span>Mission 数量</span><strong>{workItem.missions.length}</strong></div>
        <div><span>数据边界</span><strong>短控制状态</strong></div>
      </section>

      <section className="mission-stack" aria-label="Mission 与 Run 时间线">
        {workItem.missions.length === 0 ? (
          <EmptyState title="没有 Mission" description="这项工作当前没有可展示的执行合同。" />
        ) : (
          workItem.missions.map((mission) => (
            <MissionCard key={mission.missionId} mission={mission} />
          ))
        )}
      </section>
    </AppShell>
  );
}

async function loadWorkItemState(workItemId: string): Promise<
  | { kind: "loaded"; workItem: WorkItemDetail }
  | { kind: "error"; error: unknown }
> {
  try {
    return { kind: "loaded", workItem: await getWorkItem(workItemId) };
  } catch (error) {
    return { kind: "error", error };
  }
}

function MissionCard({ mission }: { mission: MissionDetail }) {
  return (
    <article className="mission-card">
      <header>
        <div>
          <p>MISSION · {mission.missionId}</p>
          <h2>{mission.objective}</h2>
        </div>
        <StatusBadge status={mission.status} />
      </header>

      <dl className="mission-contract">
        <div>
          <dt>验收摘要</dt>
          <dd>{mission.acceptanceSummary}</dd>
        </div>
        <div>
          <dt>允许的副作用</dt>
          <dd>{mission.authorizedSideEffectsSummary}</dd>
        </div>
        <div>
          <dt>执行配置</dt>
          <dd>{mission.runtimeKind} · {mission.executionProfile}</dd>
        </div>
        <div>
          <dt>目标节点</dt>
          <dd>{mission.targetNodeId}</dd>
        </div>
      </dl>

      <div className="run-timeline">
        <h3>Run 时间线</h3>
        {mission.runs.length === 0 ? (
          <p className="run-timeline__empty">尚未开始执行</p>
        ) : (
          <ol>
            {mission.runs.map((run) => (
              <li key={run.runId}>
                <span className="timeline-dot" aria-hidden="true" />
                <div>
                  <header>
                    <strong>{run.runId}</strong>
                    <StatusBadge status={run.status} />
                  </header>
                  <p>{run.resultSummary ?? run.progressSummary ?? "Service 尚未收到进度摘要。"}</p>
                  <small>
                    {run.nodeId} · {formatTimestamp(run.updatedAt)}
                    {run.resumable ? " · 可恢复" : ""}
                  </small>
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>
    </article>
  );
}
