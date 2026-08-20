import type { Metadata } from "next";
import { AppShell } from "../_components/AppShell";
import { EmptyState } from "../_components/EmptyState";
import { ServiceProblem } from "../_components/ServiceProblem";
import { StatusBadge } from "../_components/StatusBadge";
import { formatTimestamp } from "../_lib/presentation";
import {
  listInteractions,
  type InteractionSummary,
} from "../_lib/workbench-service";
import { requireChatGPTUser } from "../chatgpt-auth";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "输入记录",
};

export default async function InteractionsPage() {
  await requireChatGPTUser("/interactions");
  const interactionState = await loadInteractionState();

  if (interactionState.kind === "error") {
    return (
      <AppShell
        currentPath="/interactions"
        eyebrow="INTERACTIONS"
        title="输入与恢复状态"
        description="无法取得 Interaction 短投影。"
      >
        <ServiceProblem error={interactionState.error} />
      </AppShell>
    );
  }

  return (
    <AppShell
      currentPath="/interactions"
      eyebrow="INTERACTIONS"
      title="输入与恢复状态"
      description="只读展示 Interaction 生命周期和允许决策；页面不审批、不回复，也不读取完整 Agent prompt。"
    >
      <section className="panel interactions-panel" aria-labelledby="interaction-list-title">
        <header className="panel-heading">
          <h2 id="interaction-list-title">Interaction 列表</h2>
          <span>{interactionState.interactions.length} 项</span>
        </header>
        {interactionState.interactions.length === 0 ? (
          <EmptyState title="没有 Interaction" description="需要澄清或审批的 checkpoint 会出现在这里。" />
        ) : (
          <ul className="interaction-list">
            {interactionState.interactions.map((interaction) => (
              <li key={interaction.interactionId}>
                <header>
                  <span>INTERACTION · {interaction.interactionId}</span>
                  <StatusBadge status={interaction.state} />
                </header>
                <p>{interaction.promptSummary}</p>
                <dl>
                  <div><dt>Run</dt><dd>{interaction.runId}</dd></div>
                  <div><dt>Checkpoint</dt><dd>{interaction.checkpointId}</dd></div>
                  <div>
                    <dt>允许决策</dt>
                    <dd>{interaction.allowedDecisions.join(" / ")}</dd>
                  </div>
                  <div><dt>创建时间</dt><dd>{formatTimestamp(interaction.createdAt)}</dd></div>
                  <div>
                    <dt>解决时间</dt>
                    <dd>{interaction.resolvedAt ? formatTimestamp(interaction.resolvedAt) : "尚未解决"}</dd>
                  </div>
                  <div>
                    <dt>消费时间</dt>
                    <dd>{interaction.consumedAt ? formatTimestamp(interaction.consumedAt) : "尚未消费"}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        )}
      </section>
    </AppShell>
  );
}

async function loadInteractionState(): Promise<
  | { kind: "loaded"; interactions: InteractionSummary[] }
  | { kind: "error"; error: unknown }
> {
  try {
    return { kind: "loaded", interactions: await listInteractions() };
  } catch (error) {
    return { kind: "error", error };
  }
}
