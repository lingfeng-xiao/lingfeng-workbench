const STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  READY: "待开始",
  IN_PROGRESS: "执行中",
  REVIEW: "待验收",
  DONE: "已完成",
  ARCHIVED: "已归档",
  CANCELLED: "已取消",
  NOT_REQUESTED: "未请求验收",
  PENDING: "待人工确认",
  ACCEPTED: "已验收",
  CHANGES_REQUESTED: "已退回",
  RUN_FAILED: "执行失败",
  RUN_UNCERTAIN: "结果不确定",
  NODE_OFFLINE: "Node 离线",
  STALE: "状态过期",
  WAITING_INPUT: "等待输入",
  APPROVAL_REQUIRED: "需要批准",
  NONE: "无需关注",
  not_started: "尚未执行",
  open: "待执行",
  in_progress: "进行中",
  completed: "已完成",
  attention_required: "需关注",
  cancelled: "已取消",
  pending: "等待中",
  assigned: "已分配",
  running: "执行中",
  waiting_interaction: "等待输入",
  cancelling: "取消中",
  failed: "失败",
  interrupted: "已中断",
  uncertain: "结果不可信，需要关注",
  resolved: "已解决",
  delivered: "已送达",
  consumed: "已消费",
  expired: "已过期",
  leased: "投递中",
  dead_letter: "投递失败",
  online: "在线",
  offline: "离线",
};

const CALM_STATUSES = new Set(["DONE", "ACCEPTED", "completed", "online", "resolved", "consumed", "delivered"]);
const ACTIVE_STATUSES = new Set(["READY", "IN_PROGRESS", "in_progress", "running", "assigned", "leased", "cancelling"]);
const ATTENTION_STATUSES = new Set([
  "attention_required",
  "failed",
  "uncertain",
  "waiting_interaction",
  "pending",
  "dead_letter",
  "REVIEW",
  "PENDING",
  "CHANGES_REQUESTED",
  "RUN_FAILED",
  "RUN_UNCERTAIN",
  "NODE_OFFLINE",
  "STALE",
  "WAITING_INPUT",
  "APPROVAL_REQUIRED",
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
