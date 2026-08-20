const WORK_ITEM_STATUSES = [
  "open",
  "in_progress",
  "completed",
  "attention_required",
  "cancelled",
] as const;
const MISSION_STATUSES = [
  "assigned",
  "running",
  "waiting_interaction",
  "completed",
  "failed",
  "interrupted",
  "uncertain",
  "cancelled",
] as const;
const RUN_STATUSES = [
  "assigned",
  "running",
  "waiting_interaction",
  "cancelling",
  "completed",
  "failed",
  "interrupted",
  "uncertain",
  "cancelled",
] as const;
const INTERACTION_STATES = [
  "pending",
  "resolved",
  "delivered",
  "consumed",
  "expired",
  "cancelled",
] as const;
const DECISIONS = ["APPROVE", "REJECT", "PROVIDE_INPUT"] as const;
const NOTIFICATION_STATUSES = [
  "pending",
  "leased",
  "delivered",
  "dead_letter",
] as const;
const NOTIFICATION_TYPES = [
  "INTERACTION_REQUIRED",
  "RUN_COMPLETED",
  "RUN_FAILED",
  "RUN_UNCERTAIN",
  "NODE_OFFLINE_WITH_ACTIVE_RUN",
] as const;
const NODE_STATUSES = ["online", "offline"] as const;

export type WorkItemStatus = (typeof WORK_ITEM_STATUSES)[number];
export type MissionStatus = (typeof MISSION_STATUSES)[number];
export type RunStatus = (typeof RUN_STATUSES)[number];
export type InteractionState = (typeof INTERACTION_STATES)[number];
export type Decision = (typeof DECISIONS)[number];
export type NotificationStatus = (typeof NOTIFICATION_STATUSES)[number];
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];
export type NodeStatus = (typeof NODE_STATUSES)[number];

export interface WorkItemSummary {
  workItemId: string;
  title: string;
  status: WorkItemStatus;
  priority: number;
  phaseCode: string | null;
  progressSummary: string | null;
  waitingInteractionCount: number;
  lastSyncedAt: string | null;
  updatedAt: string;
}

export interface MissionProjection {
  missionId: string;
  revision: number;
  objective: string;
  acceptanceSummary: string;
  status: MissionStatus;
}

export interface RunProjection {
  runId: string;
  nodeId: string;
  status: RunStatus;
  phaseCode: string | null;
  progressSummary: string | null;
  resultSummary: string | null;
  resumable: boolean;
  lastSyncedAt: string;
}

export interface InteractionSummary {
  interactionId: string;
  runId: string;
  checkpointId: string;
  state: InteractionState;
  promptSummary: string;
  allowedDecisions: Decision[];
  responseSummary: string | null;
  resolvedAt: string | null;
  consumedAt: string | null;
  createdAt: string;
}

export interface NotificationProjection {
  notificationId: string;
  notificationType: NotificationType;
  status: NotificationStatus;
  createdAt: string;
}

export interface TimelineEvent {
  eventId: string;
  eventType: string;
  summary: string | null;
  createdAt: string;
}

export interface WorkItemDetail {
  workItemId: string;
  title: string;
  status: WorkItemStatus;
  priority: number;
  mission: MissionProjection;
  run: RunProjection;
  interactions: InteractionSummary[];
  notifications: NotificationProjection[];
  timeline: TimelineEvent[];
  updatedAt: string;
}

export interface NodeSummary {
  nodeId: string;
  displayName: string;
  status: NodeStatus;
  capabilities: string[];
  currentRunId: string | null;
  lastHeartbeatAt: string;
  lastSyncedAt: string;
}

export function parseWorkItemList(payload: unknown): WorkItemSummary[] {
  return readArray(payload, "work item list", 100).map(parseWorkItemSummary);
}

export function parseWorkItemDetail(payload: unknown): WorkItemDetail {
  const workItem = readStrictObject(payload, "work item", [
    "workItemId",
    "title",
    "status",
    "priority",
    "mission",
    "run",
    "interactions",
    "notifications",
    "timeline",
    "updatedAt",
  ]);
  return {
    workItemId: readPrefixedId(workItem.workItemId, "wi_", "workItemId"),
    title: readShortText(workItem.title, "title"),
    status: readEnum(workItem.status, WORK_ITEM_STATUSES, "work item status"),
    priority: readBoundedInteger(workItem.priority, -100, 100, "priority"),
    mission: parseMissionProjection(workItem.mission),
    run: parseRunProjection(workItem.run),
    interactions: readArray(workItem.interactions, "interaction list", 100).map(
      parseInteractionSummary,
    ),
    notifications: readArray(workItem.notifications, "notification list", 100).map(
      parseNotificationProjection,
    ),
    timeline: readArray(workItem.timeline, "timeline", 100).map(parseTimelineEvent),
    updatedAt: readTimestamp(workItem.updatedAt, "updatedAt"),
  };
}

export function parseInteractionList(payload: unknown): InteractionSummary[] {
  return readArray(payload, "interaction list", 100).map(parseInteractionSummary);
}

export function parseNodeList(payload: unknown): NodeSummary[] {
  return readArray(payload, "node list", 100).map(parseNodeSummary);
}

export function assertWorkItemPathId(workItemId: string): void {
  readPrefixedId(workItemId, "wi_", "workItemId");
}

function parseWorkItemSummary(payload: unknown): WorkItemSummary {
  const workItem = readStrictObject(payload, "work item", [
    "workItemId",
    "title",
    "status",
    "priority",
    "updatedAt",
  ], ["phaseCode", "progressSummary", "waitingInteractionCount", "lastSyncedAt"]);
  return {
    workItemId: readPrefixedId(workItem.workItemId, "wi_", "workItemId"),
    title: readShortText(workItem.title, "title"),
    status: readEnum(workItem.status, WORK_ITEM_STATUSES, "work item status"),
    priority: readBoundedInteger(workItem.priority, -100, 100, "priority"),
    phaseCode: readOptionalIdentifier(workItem.phaseCode, "phaseCode"),
    progressSummary: readOptionalShortText(workItem.progressSummary, "progressSummary"),
    waitingInteractionCount: readOptionalNonNegativeInteger(
      workItem.waitingInteractionCount,
      "waitingInteractionCount",
    ),
    lastSyncedAt: readOptionalTimestamp(workItem.lastSyncedAt, "lastSyncedAt"),
    updatedAt: readTimestamp(workItem.updatedAt, "updatedAt"),
  };
}

function parseMissionProjection(payload: unknown): MissionProjection {
  const mission = readStrictObject(payload, "mission", [
    "missionId",
    "revision",
    "objective",
    "acceptanceSummary",
    "status",
  ]);
  return {
    missionId: readPrefixedId(mission.missionId, "mi_", "missionId"),
    revision: readBoundedInteger(mission.revision, 1, Number.MAX_SAFE_INTEGER, "revision"),
    objective: readShortText(mission.objective, "objective"),
    acceptanceSummary: readShortText(mission.acceptanceSummary, "acceptanceSummary"),
    status: readEnum(mission.status, MISSION_STATUSES, "mission status"),
  };
}

function parseRunProjection(payload: unknown): RunProjection {
  const run = readStrictObject(payload, "run", [
    "runId",
    "nodeId",
    "status",
    "resumable",
    "lastSyncedAt",
  ], ["phaseCode", "progressSummary", "resultSummary"]);
  return {
    runId: readPrefixedId(run.runId, "run_", "runId"),
    nodeId: readIdentifier(run.nodeId, "nodeId"),
    status: readEnum(run.status, RUN_STATUSES, "run status"),
    phaseCode: readOptionalIdentifier(run.phaseCode, "phaseCode"),
    progressSummary: readOptionalShortText(run.progressSummary, "progressSummary"),
    resultSummary: readOptionalShortText(run.resultSummary, "resultSummary"),
    resumable: readBoolean(run.resumable, "resumable"),
    lastSyncedAt: readTimestamp(run.lastSyncedAt, "lastSyncedAt"),
  };
}

function parseInteractionSummary(payload: unknown): InteractionSummary {
  const interaction = readStrictObject(payload, "interaction", [
    "interactionId",
    "runId",
    "checkpointId",
    "state",
    "promptSummary",
    "allowedDecisions",
    "createdAt",
  ], ["responseSummary", "resolvedAt", "consumedAt"]);
  const allowedDecisions = readArray(
    interaction.allowedDecisions,
    "allowed decisions",
    3,
    1,
  ).map((decision) => readEnum(decision, DECISIONS, "decision"));
  if (new Set(allowedDecisions).size !== allowedDecisions.length) {
    throw new ContractParseError("allowed decisions must be unique");
  }
  return {
    interactionId: readPrefixedId(interaction.interactionId, "int_", "interactionId"),
    runId: readPrefixedId(interaction.runId, "run_", "runId"),
    checkpointId: readIdentifier(interaction.checkpointId, "checkpointId"),
    state: readEnum(interaction.state, INTERACTION_STATES, "interaction state"),
    promptSummary: readShortText(interaction.promptSummary, "promptSummary"),
    allowedDecisions,
    responseSummary: readOptionalShortText(interaction.responseSummary, "responseSummary"),
    resolvedAt: readOptionalTimestamp(interaction.resolvedAt, "resolvedAt"),
    consumedAt: readOptionalTimestamp(interaction.consumedAt, "consumedAt"),
    createdAt: readTimestamp(interaction.createdAt, "createdAt"),
  };
}

function parseNotificationProjection(payload: unknown): NotificationProjection {
  const notification = readStrictObject(payload, "notification", [
    "notificationId",
    "notificationType",
    "status",
    "createdAt",
  ]);
  return {
    notificationId: readPrefixedId(notification.notificationId, "ntf_", "notificationId"),
    notificationType: readEnum(
      notification.notificationType,
      NOTIFICATION_TYPES,
      "notification type",
    ),
    status: readEnum(notification.status, NOTIFICATION_STATUSES, "notification status"),
    createdAt: readTimestamp(notification.createdAt, "createdAt"),
  };
}

function parseTimelineEvent(payload: unknown): TimelineEvent {
  const event = readStrictObject(payload, "timeline event", [
    "eventId",
    "eventType",
    "createdAt",
  ], ["summary"]);
  return {
    eventId: readIdentifier(event.eventId, "eventId"),
    eventType: readIdentifier(event.eventType, "eventType"),
    summary: readOptionalShortText(event.summary, "summary"),
    createdAt: readTimestamp(event.createdAt, "createdAt"),
  };
}

function parseNodeSummary(payload: unknown): NodeSummary {
  const node = readStrictObject(payload, "node", [
    "nodeId",
    "displayName",
    "status",
    "capabilities",
    "lastHeartbeatAt",
    "lastSyncedAt",
  ], ["currentRunId"]);
  const capabilities = readArray(node.capabilities, "capability list", 32).map(
    (capability) => readIdentifier(capability, "capability"),
  );
  if (new Set(capabilities).size !== capabilities.length) {
    throw new ContractParseError("capabilities must be unique");
  }
  return {
    nodeId: readIdentifier(node.nodeId, "nodeId"),
    displayName: readShortText(node.displayName, "displayName"),
    status: readEnum(node.status, NODE_STATUSES, "node status"),
    capabilities,
    currentRunId:
      node.currentRunId === undefined
        ? null
        : readPrefixedId(node.currentRunId, "run_", "currentRunId"),
    lastHeartbeatAt: readTimestamp(node.lastHeartbeatAt, "lastHeartbeatAt"),
    lastSyncedAt: readTimestamp(node.lastSyncedAt, "lastSyncedAt"),
  };
}

export class ContractParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ContractParseError";
  }
}

function readStrictObject(
  payload: unknown,
  label: string,
  requiredKeys: readonly string[],
  optionalKeys: readonly string[] = [],
): Record<string, unknown> {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new ContractParseError(`${label} must be an object`);
  }
  const object = payload as Record<string, unknown>;
  const allowedKeys = new Set([...requiredKeys, ...optionalKeys]);
  for (const key of Object.keys(object)) {
    if (!allowedKeys.has(key)) {
      throw new ContractParseError(`${label} contains unknown field ${key}`);
    }
  }
  for (const key of requiredKeys) {
    if (!Object.hasOwn(object, key)) {
      throw new ContractParseError(`${label} is missing ${key}`);
    }
  }
  return object;
}

function readArray(
  payload: unknown,
  label: string,
  maximumLength: number,
  minimumLength = 0,
): unknown[] {
  if (
    !Array.isArray(payload) ||
    payload.length < minimumLength ||
    payload.length > maximumLength
  ) {
    throw new ContractParseError(`${label} has an invalid length`);
  }
  return payload;
}

function readShortText(payload: unknown, label: string): string {
  const characterLength = typeof payload === "string" ? Array.from(payload).length : 0;
  if (typeof payload !== "string" || characterLength < 1 || characterLength > 800) {
    throw new ContractParseError(`${label} must contain 1 to 800 characters`);
  }
  return payload;
}

function readOptionalShortText(payload: unknown, label: string): string | null {
  return payload === undefined ? null : readShortText(payload, label);
}

function readIdentifier(payload: unknown, label: string): string {
  if (
    typeof payload !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(payload)
  ) {
    throw new ContractParseError(`${label} is not a valid identifier`);
  }
  return payload;
}

function readOptionalIdentifier(payload: unknown, label: string): string | null {
  return payload === undefined ? null : readIdentifier(payload, label);
}

function readPrefixedId(payload: unknown, prefix: string, label: string): string {
  if (
    typeof payload !== "string" ||
    !new RegExp(`^${prefix}[A-Za-z0-9]+$`).test(payload)
  ) {
    throw new ContractParseError(`${label} is not a valid ${prefix} identifier`);
  }
  return payload;
}

function readTimestamp(payload: unknown, label: string): string {
  if (
    typeof payload !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(payload) ||
    Number.isNaN(Date.parse(payload))
  ) {
    throw new ContractParseError(`${label} is not a timestamp`);
  }
  return payload;
}

function readOptionalTimestamp(payload: unknown, label: string): string | null {
  return payload === undefined ? null : readTimestamp(payload, label);
}

function readBoundedInteger(
  payload: unknown,
  minimum: number,
  maximum: number,
  label: string,
): number {
  if (
    !Number.isSafeInteger(payload) ||
    (payload as number) < minimum ||
    (payload as number) > maximum
  ) {
    throw new ContractParseError(`${label} is outside its allowed range`);
  }
  return payload as number;
}

function readOptionalNonNegativeInteger(payload: unknown, label: string): number {
  return payload === undefined
    ? 0
    : readBoundedInteger(payload, 0, Number.MAX_SAFE_INTEGER, label);
}

function readBoolean(payload: unknown, label: string): boolean {
  if (typeof payload !== "boolean") {
    throw new ContractParseError(`${label} must be boolean`);
  }
  return payload;
}

function readEnum<const Values extends readonly string[]>(
  payload: unknown,
  values: Values,
  label: string,
): Values[number] {
  if (typeof payload !== "string" || !values.includes(payload)) {
    throw new ContractParseError(`${label} is unknown`);
  }
  return payload as Values[number];
}
