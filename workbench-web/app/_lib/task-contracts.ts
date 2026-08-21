const BUSINESS_STATUSES = [
  "DRAFT",
  "READY",
  "IN_PROGRESS",
  "REVIEW",
  "DONE",
  "ARCHIVED",
  "CANCELLED",
] as const;
const ACCEPTANCE_STATUSES = [
  "NOT_REQUESTED",
  "PENDING",
  "ACCEPTED",
  "CHANGES_REQUESTED",
] as const;
const ATTENTION_STATES = [
  "NONE",
  "WAITING_INPUT",
  "APPROVAL_REQUIRED",
  "RUN_FAILED",
  "RUN_UNCERTAIN",
  "NODE_OFFLINE",
  "STALE",
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
const ALLOWED_ACTIONS = [
  "EDIT",
  "MARK_READY",
  "START",
  "ACCEPT",
  "REQUEST_CHANGES",
  "CANCEL",
  "ARCHIVE",
  "RESTORE",
] as const;

export type BusinessStatus = (typeof BUSINESS_STATUSES)[number];
export type AcceptanceStatus = (typeof ACCEPTANCE_STATUSES)[number];
export type AttentionState = (typeof ATTENTION_STATES)[number];
export type TaskAction = (typeof ALLOWED_ACTIONS)[number];

export interface ContextRef {
  ref: string;
  label: string;
}

export interface TaskSummary {
  taskId: string;
  title: string;
  priority: number;
  targetNodeId: string;
  businessStatus: BusinessStatus;
  acceptanceStatus: AcceptanceStatus;
  attentionState: AttentionState;
  version: number;
  runStatus: string | null;
  progressSummary: string | null;
  lastObservedAt: string | null;
  stale: boolean;
  nodeStatus: "online" | "offline";
  updatedAt: string;
}

export interface TaskRun {
  workItemId: string;
  missionId: string;
  runId: string;
  missionRevision: number;
  status: (typeof RUN_STATUSES)[number];
  phaseCode: string | null;
  progressSummary: string | null;
  resultSummary: string | null;
  lastObservedAt: string | null;
  stale: boolean;
  createdAt: string;
}

export interface TaskEvent {
  eventId: string;
  sequence: number;
  eventType: string;
  summary: string;
  actor: string;
  source: "USER" | "SERVICE" | "NODE";
  workItemId: string | null;
  missionId: string | null;
  runId: string | null;
  occurredAt: string;
}

export interface TaskDetail {
  taskId: string;
  title: string;
  objective: string;
  acceptanceSummary: string;
  sideEffectSummary: string;
  priority: number;
  targetNodeId: string;
  workspaceRef: string;
  contextRefs: ContextRef[];
  runtimeKind: string;
  executionProfile: string;
  businessStatus: BusinessStatus;
  acceptanceStatus: AcceptanceStatus;
  attentionState: AttentionState;
  deliverySummary: string | null;
  commitSha: string | null;
  prUrl: string | null;
  version: number;
  allowedActions: TaskAction[];
  nodeStatus: "online" | "offline";
  nodeLastHeartbeatAt: string | null;
  runs: TaskRun[];
  timeline: TaskEvent[];
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
}

export interface CreatedTask {
  taskId: string;
  version: number;
  businessStatus: "DRAFT";
  createdAt: string;
}

export interface StartedTask {
  taskId: string;
  version: number;
  workItemId: string;
  missionId: string;
  runId: string;
  businessStatus: "IN_PROGRESS";
  startedAt: string;
}

export class TaskContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TaskContractError";
  }
}

export function assertTaskId(taskId: string): void {
  readPrefixedId(taskId, "task_", "taskId");
}

export function parseTaskList(payload: unknown): TaskSummary[] {
  return readArray(payload, "Task list", 100).map(parseTaskSummary);
}

export function parseTaskDetail(payload: unknown): TaskDetail {
  const task = strictObject(
    payload,
    "Task detail",
    [
      "taskId",
      "title",
      "objective",
      "acceptanceSummary",
      "sideEffectSummary",
      "priority",
      "targetNodeId",
      "workspaceRef",
      "contextRefs",
      "runtimeKind",
      "executionProfile",
      "businessStatus",
      "acceptanceStatus",
      "attentionState",
      "version",
      "allowedActions",
      "nodeStatus",
      "runs",
      "timeline",
      "createdAt",
      "updatedAt",
    ],
    [
      "deliverySummary",
      "commitSha",
      "prUrl",
      "nodeLastHeartbeatAt",
      "archivedAt",
    ],
  );
  const actions = readArray(task.allowedActions, "allowedActions", 8).map((action) =>
    readEnum(action, ALLOWED_ACTIONS, "allowedAction"),
  );
  return {
    taskId: readPrefixedId(task.taskId, "task_", "taskId"),
    title: readText(task.title, "title", 200),
    objective: readText(task.objective, "objective"),
    acceptanceSummary: readText(task.acceptanceSummary, "acceptanceSummary"),
    sideEffectSummary: readText(task.sideEffectSummary, "sideEffectSummary"),
    priority: readInteger(task.priority, -100, 100, "priority"),
    targetNodeId: readIdentifier(task.targetNodeId, "targetNodeId"),
    workspaceRef: readIdentifier(task.workspaceRef, "workspaceRef"),
    contextRefs: readArray(task.contextRefs, "contextRefs", 16, 1).map(parseContextRef),
    runtimeKind: readIdentifier(task.runtimeKind, "runtimeKind"),
    executionProfile: readIdentifier(task.executionProfile, "executionProfile"),
    businessStatus: readEnum(task.businessStatus, BUSINESS_STATUSES, "businessStatus"),
    acceptanceStatus: readEnum(
      task.acceptanceStatus,
      ACCEPTANCE_STATUSES,
      "acceptanceStatus",
    ),
    attentionState: readEnum(task.attentionState, ATTENTION_STATES, "attentionState"),
    deliverySummary: readOptionalText(task.deliverySummary, "deliverySummary"),
    commitSha: readOptionalPattern(task.commitSha, /^[a-f0-9]{7,64}$/, "commitSha"),
    prUrl: readOptionalHttpsUrl(task.prUrl, "prUrl"),
    version: readInteger(task.version, 1, Number.MAX_SAFE_INTEGER, "version"),
    allowedActions: unique(actions, "allowedActions"),
    nodeStatus: readEnum(task.nodeStatus, ["online", "offline"] as const, "nodeStatus"),
    nodeLastHeartbeatAt: readOptionalTimestamp(
      task.nodeLastHeartbeatAt,
      "nodeLastHeartbeatAt",
    ),
    runs: readArray(task.runs, "runs", 100).map(parseTaskRun),
    timeline: readArray(task.timeline, "timeline", 100).map(parseTaskEvent),
    createdAt: readTimestamp(task.createdAt, "createdAt"),
    updatedAt: readTimestamp(task.updatedAt, "updatedAt"),
    archivedAt: readOptionalTimestamp(task.archivedAt, "archivedAt"),
  };
}

export function parseCreatedTask(payload: unknown): CreatedTask {
  const task = strictObject(payload, "Created Task", [
    "taskId",
    "version",
    "businessStatus",
    "createdAt",
  ]);
  if (task.businessStatus !== "DRAFT") throw new TaskContractError("Created Task is not DRAFT");
  return {
    taskId: readPrefixedId(task.taskId, "task_", "taskId"),
    version: readInteger(task.version, 1, Number.MAX_SAFE_INTEGER, "version"),
    businessStatus: "DRAFT",
    createdAt: readTimestamp(task.createdAt, "createdAt"),
  };
}

export function parseStartedTask(payload: unknown): StartedTask {
  const task = strictObject(payload, "Started Task", [
    "taskId",
    "version",
    "workItemId",
    "missionId",
    "runId",
    "businessStatus",
    "startedAt",
  ]);
  if (task.businessStatus !== "IN_PROGRESS") {
    throw new TaskContractError("Started Task is not IN_PROGRESS");
  }
  return {
    taskId: readPrefixedId(task.taskId, "task_", "taskId"),
    version: readInteger(task.version, 1, Number.MAX_SAFE_INTEGER, "version"),
    workItemId: readPrefixedId(task.workItemId, "wi_", "workItemId"),
    missionId: readPrefixedId(task.missionId, "mi_", "missionId"),
    runId: readPrefixedId(task.runId, "run_", "runId"),
    businessStatus: "IN_PROGRESS",
    startedAt: readTimestamp(task.startedAt, "startedAt"),
  };
}

function parseTaskSummary(payload: unknown): TaskSummary {
  const task = strictObject(
    payload,
    "Task summary",
    [
      "taskId",
      "title",
      "priority",
      "targetNodeId",
      "businessStatus",
      "acceptanceStatus",
      "attentionState",
      "version",
      "stale",
      "nodeStatus",
      "updatedAt",
    ],
    ["runStatus", "progressSummary", "lastObservedAt"],
  );
  return {
    taskId: readPrefixedId(task.taskId, "task_", "taskId"),
    title: readText(task.title, "title", 200),
    priority: readInteger(task.priority, -100, 100, "priority"),
    targetNodeId: readIdentifier(task.targetNodeId, "targetNodeId"),
    businessStatus: readEnum(task.businessStatus, BUSINESS_STATUSES, "businessStatus"),
    acceptanceStatus: readEnum(
      task.acceptanceStatus,
      ACCEPTANCE_STATUSES,
      "acceptanceStatus",
    ),
    attentionState: readEnum(task.attentionState, ATTENTION_STATES, "attentionState"),
    version: readInteger(task.version, 1, Number.MAX_SAFE_INTEGER, "version"),
    runStatus: readOptionalIdentifier(task.runStatus, "runStatus"),
    progressSummary: readOptionalText(task.progressSummary, "progressSummary"),
    lastObservedAt: readOptionalTimestamp(task.lastObservedAt, "lastObservedAt"),
    stale: readBoolean(task.stale, "stale"),
    nodeStatus: readEnum(task.nodeStatus, ["online", "offline"] as const, "nodeStatus"),
    updatedAt: readTimestamp(task.updatedAt, "updatedAt"),
  };
}

function parseContextRef(payload: unknown): ContextRef {
  const contextRef = strictObject(payload, "contextRef", ["ref", "label"]);
  return {
    ref: readIdentifier(contextRef.ref, "contextRef.ref"),
    label: readText(contextRef.label, "contextRef.label", 200),
  };
}

function parseTaskRun(payload: unknown): TaskRun {
  const run = strictObject(
    payload,
    "Task Run",
    [
      "workItemId",
      "missionId",
      "runId",
      "missionRevision",
      "status",
      "stale",
      "createdAt",
    ],
    ["phaseCode", "progressSummary", "resultSummary", "lastObservedAt"],
  );
  return {
    workItemId: readPrefixedId(run.workItemId, "wi_", "workItemId"),
    missionId: readPrefixedId(run.missionId, "mi_", "missionId"),
    runId: readPrefixedId(run.runId, "run_", "runId"),
    missionRevision: readInteger(
      run.missionRevision,
      1,
      Number.MAX_SAFE_INTEGER,
      "missionRevision",
    ),
    status: readEnum(run.status, RUN_STATUSES, "runStatus"),
    phaseCode: readOptionalIdentifier(run.phaseCode, "phaseCode"),
    progressSummary: readOptionalText(run.progressSummary, "progressSummary"),
    resultSummary: readOptionalText(run.resultSummary, "resultSummary"),
    lastObservedAt: readOptionalTimestamp(run.lastObservedAt, "lastObservedAt"),
    stale: readBoolean(run.stale, "stale"),
    createdAt: readTimestamp(run.createdAt, "createdAt"),
  };
}

function parseTaskEvent(payload: unknown): TaskEvent {
  const event = strictObject(
    payload,
    "Task event",
    ["eventId", "sequence", "eventType", "summary", "actor", "source", "occurredAt"],
    ["workItemId", "missionId", "runId"],
  );
  return {
    eventId: readIdentifier(event.eventId, "eventId"),
    sequence: readInteger(event.sequence, 1, Number.MAX_SAFE_INTEGER, "sequence"),
    eventType: readIdentifier(event.eventType, "eventType"),
    summary: readText(event.summary, "summary"),
    actor: readIdentifier(event.actor, "actor"),
    source: readEnum(event.source, ["USER", "SERVICE", "NODE"] as const, "source"),
    workItemId: readOptionalPrefixedId(event.workItemId, "wi_", "workItemId"),
    missionId: readOptionalPrefixedId(event.missionId, "mi_", "missionId"),
    runId: readOptionalPrefixedId(event.runId, "run_", "runId"),
    occurredAt: readTimestamp(event.occurredAt, "occurredAt"),
  };
}

function strictObject(
  payload: unknown,
  label: string,
  required: readonly string[],
  optional: readonly string[] = [],
): Record<string, unknown> {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new TaskContractError(`${label} must be an object`);
  }
  const object = payload as Record<string, unknown>;
  const allowed = new Set([...required, ...optional]);
  for (const key of Object.keys(object)) {
    if (!allowed.has(key)) throw new TaskContractError(`${label} contains unknown field ${key}`);
  }
  for (const key of required) {
    if (!Object.hasOwn(object, key)) throw new TaskContractError(`${label} is missing ${key}`);
  }
  return object;
}

function readArray(payload: unknown, label: string, maximum: number, minimum = 0): unknown[] {
  if (!Array.isArray(payload) || payload.length < minimum || payload.length > maximum) {
    throw new TaskContractError(`${label} has an invalid length`);
  }
  return payload;
}

function readText(payload: unknown, label: string, maximum = 800): string {
  const length = typeof payload === "string" ? Array.from(payload).length : 0;
  if (typeof payload !== "string" || length < 1 || length > maximum) {
    throw new TaskContractError(`${label} has an invalid length`);
  }
  return payload;
}

function readOptionalText(payload: unknown, label: string): string | null {
  return payload === undefined || payload === null ? null : readText(payload, label);
}

function readIdentifier(payload: unknown, label: string): string {
  if (typeof payload !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(payload)) {
    throw new TaskContractError(`${label} is not an identifier`);
  }
  return payload;
}

function readOptionalIdentifier(payload: unknown, label: string): string | null {
  return payload === undefined || payload === null ? null : readIdentifier(payload, label);
}

function readPrefixedId(payload: unknown, prefix: string, label: string): string {
  if (typeof payload !== "string" || !new RegExp(`^${prefix}[A-Za-z0-9]+$`).test(payload)) {
    throw new TaskContractError(`${label} is invalid`);
  }
  return payload;
}

function readOptionalPrefixedId(payload: unknown, prefix: string, label: string): string | null {
  return payload === undefined || payload === null ? null : readPrefixedId(payload, prefix, label);
}

function readInteger(payload: unknown, minimum: number, maximum: number, label: string): number {
  if (!Number.isSafeInteger(payload) || (payload as number) < minimum || (payload as number) > maximum) {
    throw new TaskContractError(`${label} is outside its range`);
  }
  return payload as number;
}

function readBoolean(payload: unknown, label: string): boolean {
  if (typeof payload !== "boolean") throw new TaskContractError(`${label} is not boolean`);
  return payload;
}

function readTimestamp(payload: unknown, label: string): string {
  if (typeof payload !== "string" || Number.isNaN(Date.parse(payload))) {
    throw new TaskContractError(`${label} is not a timestamp`);
  }
  return payload;
}

function readOptionalTimestamp(payload: unknown, label: string): string | null {
  return payload === undefined || payload === null ? null : readTimestamp(payload, label);
}

function readOptionalPattern(
  payload: unknown,
  pattern: RegExp,
  label: string,
): string | null {
  if (payload === undefined || payload === null) return null;
  if (typeof payload !== "string" || !pattern.test(payload)) {
    throw new TaskContractError(`${label} is invalid`);
  }
  return payload;
}

function readOptionalHttpsUrl(payload: unknown, label: string): string | null {
  if (payload === undefined || payload === null) return null;
  if (typeof payload !== "string") throw new TaskContractError(`${label} is invalid`);
  let url: URL;
  try {
    url = new URL(payload);
  } catch {
    throw new TaskContractError(`${label} is invalid`);
  }
  if (url.protocol !== "https:") throw new TaskContractError(`${label} must use HTTPS`);
  return url.toString();
}

function readEnum<const Values extends readonly string[]>(
  payload: unknown,
  values: Values,
  label: string,
): Values[number] {
  if (typeof payload !== "string" || !values.includes(payload)) {
    throw new TaskContractError(`${label} is unknown`);
  }
  return payload as Values[number];
}

function unique<T>(values: T[], label: string): T[] {
  if (new Set(values).size !== values.length) throw new TaskContractError(`${label} must be unique`);
  return values;
}
