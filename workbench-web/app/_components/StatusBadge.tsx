const STATUS_LABELS: Record<string, string> = {
  open: "待执行",
  in_progress: "进行中",
  completed: "已完成",
  attention_required: "需关注",
  cancelled: "已取消",
  pending: "等待中",
  assigned: "已分配",
  running: "执行中",
  waiting_interaction: "等待输入",
  failed: "失败",
  interrupted: "已中断",
  uncertain: "结果未知",
  resolved: "已解决",
  delivered: "已送达",
  consumed: "已消费",
  expired: "已过期",
  online: "在线",
  offline: "离线",
};

const CALM_STATUSES = new Set(["completed", "online", "resolved", "consumed"]);
const ACTIVE_STATUSES = new Set(["in_progress", "running", "assigned", "delivered"]);
const ATTENTION_STATUSES = new Set([
  "attention_required",
  "failed",
  "uncertain",
  "waiting_interaction",
  "pending",
]);

export function StatusBadge({ status }: { status: string }) {
  const tone = CALM_STATUSES.has(status)
    ? "calm"
    : ACTIVE_STATUSES.has(status)
      ? "active"
      : ATTENTION_STATUSES.has(status)
        ? "attention"
        : "muted";

  return (
    <span className={`status-badge status-badge--${tone}`}>
      <span aria-hidden="true" />
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}
