import type { Metadata } from "next";
import { AppShell } from "../_components/AppShell";
import { EmptyState } from "../_components/EmptyState";
import { ServiceProblem } from "../_components/ServiceProblem";
import { StatusBadge } from "../_components/StatusBadge";
import { formatTimestamp } from "../_lib/presentation";
import {
  listPendingInteractions,
  type InteractionSummary,
} from "../_lib/workbench-service";
import { requireChatGPTUser } from "../chatgpt-auth";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "待处理输入",
};

export default async function InteractionsPage() {
  await requireChatGPTUser("/interactions");
  const interactionState = await loadInteractionState();

  if (interactionState.kind === "error") {
    return (
      <AppShell
        currentPath="/interactions"
        eyebrow="PENDING INTERACTIONS"
        title="需要人的输入"
        description="无法取得待处理 Interaction。"
      >
        <ServiceProblem error={interactionState.error} />
      </AppShell>
    );
  }

  return (
    <AppShell
      currentPath="/interactions"
      eyebrow="PENDING INTERACTIONS"
      title="需要人的输入"
      description="MVP-W1 只读展示精确绑定的 Interaction，不在页面中审批或回复。"
    >
      <section className="panel interactions-panel" aria-labelledby="interaction-list-title">
        <header className="panel-heading">
          <h2 id="interaction-list-title">待处理列表</h2>
          <span>{interactionState.interactions.length} 项</span>
        </header>
        {interactionState.interactions.length === 0 ? (
          <EmptyState title="没有待处理输入" description="需要澄清或审批的 checkpoint 会出现在这里。" />
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
                  <div><dt>创建时间</dt><dd>{formatTimestamp(interaction.createdAt)}</dd></div>
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
    return { kind: "loaded", interactions: await listPendingInteractions() };
  } catch (error) {
    return { kind: "error", error };
  }
}
