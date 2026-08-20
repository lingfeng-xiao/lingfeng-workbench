import type { Metadata } from "next";
import { AppShell } from "../../_components/AppShell";
import { EmptyState } from "../../_components/EmptyState";
import { ServiceProblem } from "../../_components/ServiceProblem";
import { StatusBadge } from "../../_components/StatusBadge";
import { formatTimestamp } from "../../_lib/presentation";
import {
  getWorkItem,
  type InteractionSummary,
  type NotificationProjection,
  type TimelineEvent,
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
        <div><span>Run 状态</span><StatusBadge status={workItem.run.status} /></div>
        <div><span>最后同步</span><strong>{formatTimestamp(workItem.run.lastSyncedAt)}</strong></div>
      </section>

      <section className="mission-stack" aria-label="Mission、Run 与控制时间线">
        <article className="mission-card">
          <header>
            <div>
              <p>MISSION · {workItem.mission.missionId} · REV {workItem.mission.revision}</p>
              <h2>{workItem.mission.objective}</h2>
            </div>
            <StatusBadge status={workItem.mission.status} />
          </header>

          <dl className="mission-contract mission-contract--v2">
            <div>
              <dt>验收摘要</dt>
              <dd>{workItem.mission.acceptanceSummary}</dd>
            </div>
            <div>
              <dt>阶段</dt>
              <dd>{workItem.run.phaseCode ?? "尚未上报"}</dd>
            </div>
            <div>
              <dt>进度</dt>
              <dd>{workItem.run.progressSummary ?? "尚未上报"}</dd>
            </div>
            <div>
              <dt>恢复</dt>
              <dd>{workItem.run.resumable ? "同一 Session 可恢复" : "当前不可恢复"}</dd>
            </div>
          </dl>

          <div className="run-timeline">
            <h3>Service 短时间线</h3>
            {workItem.timeline.length === 0 ? (
              <p className="run-timeline__empty">尚无已同步时间线事件</p>
            ) : (
              <Timeline events={workItem.timeline} />
            )}
          </div>
        </article>

        <DetailInteractions interactions={workItem.interactions} />
        <NotificationList notifications={workItem.notifications} />
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

function Timeline({ events }: { events: TimelineEvent[] }) {
  return (
    <ol>
      {events.map((event) => (
        <li key={event.eventId}>
          <span className="timeline-dot" aria-hidden="true" />
          <div>
            <header><strong>{event.eventType}</strong></header>
            <p>{event.summary ?? "状态已更新"}</p>
            <small>{formatTimestamp(event.createdAt)}</small>
          </div>
        </li>
      ))}
    </ol>
  );
}

function DetailInteractions({ interactions }: { interactions: InteractionSummary[] }) {
  return (
    <article className="panel interactions-panel">
      <header className="panel-heading">
        <h2>Interaction 生命周期</h2>
        <span>{interactions.length} 项</span>
      </header>
      {interactions.length === 0 ? (
        <EmptyState title="没有 Interaction" description="本次 Run 尚未请求人的输入。" />
      ) : (
        <ul className="interaction-list interaction-list--detail">
          {interactions.map((interaction) => (
            <li key={interaction.interactionId}>
              <header>
                <span>{interaction.interactionId} · {interaction.checkpointId}</span>
                <StatusBadge status={interaction.state} />
              </header>
              <p>{interaction.promptSummary}</p>
              <small>允许决策：{interaction.allowedDecisions.join(" / ")}</small>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function NotificationList({ notifications }: { notifications: NotificationProjection[] }) {
  return (
    <article className="panel">
      <header className="panel-heading">
        <h2>重要通知投影</h2>
        <span>{notifications.length} 项</span>
      </header>
      {notifications.length === 0 ? (
        <EmptyState title="没有重要通知" description="投递状态只表示通知结果，不改变 Run 结果。" />
      ) : (
        <ul className="notification-list">
          {notifications.map((notification) => (
            <li key={notification.notificationId}>
              <div>
                <strong>{notification.notificationType}</strong>
                <small>{formatTimestamp(notification.createdAt)}</small>
              </div>
              <StatusBadge status={notification.status} />
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
