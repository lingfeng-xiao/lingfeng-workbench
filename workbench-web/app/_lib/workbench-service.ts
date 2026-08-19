import "server-only";

const CLIENT_API_PREFIX = "/api/client/v1";
const MAX_RESPONSE_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 5_000;
const MAX_TIMEOUT_MS = 30_000;

const WORK_ITEM_STATUSES = [
  "open",
  "in_progress",
  "completed",
  "attention_required",
  "cancelled",
] as const;
const MISSION_STATUSES = [
  "pending",
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
const NODE_STATUSES = ["online", "offline"] as const;

export type WorkItemStatus = (typeof WORK_ITEM_STATUSES)[number];
export type MissionStatus = (typeof MISSION_STATUSES)[number];
export type RunStatus = (typeof RUN_STATUSES)[number];
export type InteractionState = (typeof INTERACTION_STATES)[number];
export type NodeStatus = (typeof NODE_STATUSES)[number];

export interface WorkItemSummary {
  workItemId: string;
  title: string;
  status: WorkItemStatus;
  priority: number;
  updatedAt: string;
}

export interface RunSummary {
  runId: string;
  missionId: string;
  nodeId: string;
  status: RunStatus;
  progressSummary: string | null;
  resultSummary: string | null;
  resumable: boolean;
  updatedAt: string;
}

export interface MissionDetail {
  missionId: string;
  workItemId: string;
  revision: number;
  objective: string;
  acceptanceSummary: string;
  authorizedSideEffectsSummary: string;
  targetNodeId: string;
  runtimeKind: string;
  executionProfile: string;
  status: MissionStatus;
  runs: RunSummary[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkItemDetail extends WorkItemSummary {
  missions: MissionDetail[];
}

export interface NodeSummary {
  nodeId: string;
  displayName: string;
  status: NodeStatus;
  capabilities: string[];
  lastHeartbeatAt: string;
}

export interface InteractionSummary {
  interactionId: string;
  runId: string;
  checkpointId: string;
  state: InteractionState;
  promptSummary: string;
  createdAt: string;
}

export type WorkbenchServiceErrorKind =
  | "configuration"
  | "access_denied"
  | "not_found"
  | "unavailable"
  | "invalid_response";

export class WorkbenchServiceError extends Error {
  constructor(
    public readonly kind: WorkbenchServiceErrorKind,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "WorkbenchServiceError";
  }
}

export async function listWorkItems(): Promise<WorkItemSummary[]> {
  const payload = await requestServiceJson("/work-items?limit=50");
  return readArray(payload, "work item list").map(readWorkItemSummary);
}

export async function getWorkItem(workItemId: string): Promise<WorkItemDetail> {
  assertSafePathIdentifier(workItemId);
  const payload = await requestServiceJson(
    `/work-items/${encodeURIComponent(workItemId)}`,
  );
  const workItem = readObject(payload, "work item");
  return {
    ...readWorkItemSummary(workItem),
    missions: readArray(workItem.missions, "mission list").map(readMissionDetail),
  };
}

export async function listNodes(): Promise<NodeSummary[]> {
  const payload = await requestServiceJson("/nodes");
  return readArray(payload, "node list").map(readNodeSummary);
}

export async function listPendingInteractions(): Promise<InteractionSummary[]> {
  const payload = await requestServiceJson("/interactions?state=pending");
  return readArray(payload, "interaction list").map(readInteractionSummary);
}

async function requestServiceJson(path: string): Promise<unknown> {
  const configuration = readServiceConfiguration();
  let response: Response;
  try {
    response = await fetch(`${configuration.clientApiBaseUrl}${path}`, {
      method: "GET",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${configuration.readToken}`,
      },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(configuration.timeoutMs),
    });
  } catch (error) {
    throw new WorkbenchServiceError(
      "unavailable",
      "Workbench Service request failed or timed out",
      { cause: error },
    );
  }

  if (response.status === 401 || response.status === 403) {
    throw new WorkbenchServiceError(
      "access_denied",
      `Workbench Service denied the read request with status ${response.status}`,
    );
  }
  if (response.status === 404) {
    throw new WorkbenchServiceError("not_found", "Workbench entity was not found");
  }
  if (!response.ok) {
    throw new WorkbenchServiceError(
      "unavailable",
      `Workbench Service returned status ${response.status}`,
    );
  }

  const responseText = await response.text();
  if (new TextEncoder().encode(responseText).byteLength > MAX_RESPONSE_BYTES) {
    throw new WorkbenchServiceError(
      "invalid_response",
      "Workbench Service response exceeded the 64 KiB contract limit",
    );
  }

  try {
    return JSON.parse(responseText) as unknown;
  } catch (error) {
    throw new WorkbenchServiceError(
      "invalid_response",
      "Workbench Service returned invalid JSON",
      { cause: error },
    );
  }
}

function readServiceConfiguration(): {
  clientApiBaseUrl: string;
  readToken: string;
  timeoutMs: number;
} {
  const configuredBaseUrl = process.env.WORKBENCH_SERVICE_BASE_URL?.trim();
  const readToken = process.env.WORKBENCH_SERVICE_READ_TOKEN?.trim();
  if (!configuredBaseUrl || !readToken) {
    throw new WorkbenchServiceError(
      "configuration",
      "Workbench Service base URL and read-only token are required",
    );
  }

  let serviceUrl: URL;
  try {
    serviceUrl = new URL(configuredBaseUrl);
  } catch (error) {
    throw new WorkbenchServiceError(
      "configuration",
      "Workbench Service base URL is invalid",
      { cause: error },
    );
  }

  const isLocalDevelopment =
    serviceUrl.protocol === "http:" &&
    (serviceUrl.hostname === "127.0.0.1" || serviceUrl.hostname === "localhost");
  if (serviceUrl.protocol !== "https:" && !isLocalDevelopment) {
    throw new WorkbenchServiceError(
      "configuration",
      "Workbench Service must use HTTPS outside local development",
    );
  }

  const configuredTimeout = Number(process.env.WORKBENCH_SERVICE_TIMEOUT_MS);
  const timeoutMs = Number.isInteger(configuredTimeout)
    ? Math.min(Math.max(configuredTimeout, 100), MAX_TIMEOUT_MS)
    : DEFAULT_TIMEOUT_MS;
  const rootUrl = serviceUrl.toString().replace(/\/$/, "");
  return {
    clientApiBaseUrl: `${rootUrl}${CLIENT_API_PREFIX}`,
    readToken,
    timeoutMs,
  };
}

function readWorkItemSummary(payload: unknown): WorkItemSummary {
  const workItem = readObject(payload, "work item");
  return {
    workItemId: readIdentifier(workItem.workItemId, "workItemId"),
    title: readShortText(workItem.title, "title"),
    status: readEnum(workItem.status, WORK_ITEM_STATUSES, "work item status"),
    priority: readInteger(workItem.priority, "priority"),
    updatedAt: readTimestamp(workItem.updatedAt, "updatedAt"),
  };
}

function readMissionDetail(payload: unknown): MissionDetail {
  const mission = readObject(payload, "mission");
  return {
    missionId: readIdentifier(mission.missionId, "missionId"),
    workItemId: readIdentifier(mission.workItemId, "workItemId"),
    revision: readPositiveInteger(mission.revision, "revision"),
    objective: readShortText(mission.objective, "objective"),
    acceptanceSummary: readShortText(
      mission.acceptanceSummary,
      "acceptanceSummary",
    ),
    authorizedSideEffectsSummary: readShortText(
      mission.authorizedSideEffectsSummary,
      "authorizedSideEffectsSummary",
    ),
    targetNodeId: readIdentifier(mission.targetNodeId, "targetNodeId"),
    runtimeKind: readIdentifier(mission.runtimeKind, "runtimeKind"),
    executionProfile: readIdentifier(
      mission.executionProfile,
      "executionProfile",
    ),
    status: readEnum(mission.status, MISSION_STATUSES, "mission status"),
    runs: readArray(mission.runs, "run list").map(readRunSummary),
    createdAt: readTimestamp(mission.createdAt, "createdAt"),
    updatedAt: readTimestamp(mission.updatedAt, "updatedAt"),
  };
}

function readRunSummary(payload: unknown): RunSummary {
  const run = readObject(payload, "run");
  return {
    runId: readIdentifier(run.runId, "runId"),
    missionId: readIdentifier(run.missionId, "missionId"),
    nodeId: readIdentifier(run.nodeId, "nodeId"),
    status: readEnum(run.status, RUN_STATUSES, "run status"),
    progressSummary: readOptionalShortText(run.progressSummary, "progressSummary"),
    resultSummary: readOptionalShortText(run.resultSummary, "resultSummary"),
    resumable: readBoolean(run.resumable, "resumable"),
    updatedAt: readTimestamp(run.updatedAt, "updatedAt"),
  };
}

function readNodeSummary(payload: unknown): NodeSummary {
  const node = readObject(payload, "node");
  return {
    nodeId: readIdentifier(node.nodeId, "nodeId"),
    displayName: readShortText(node.displayName, "displayName"),
    status: readEnum(node.status, NODE_STATUSES, "node status"),
    capabilities: readArray(node.capabilities, "capability list").map(
      (capability) => readIdentifier(capability, "capability"),
    ),
    lastHeartbeatAt: readTimestamp(node.lastHeartbeatAt, "lastHeartbeatAt"),
  };
}

function readInteractionSummary(payload: unknown): InteractionSummary {
  const interaction = readObject(payload, "interaction");
  return {
    interactionId: readIdentifier(interaction.interactionId, "interactionId"),
    runId: readIdentifier(interaction.runId, "runId"),
    checkpointId: readIdentifier(interaction.checkpointId, "checkpointId"),
    state: readEnum(
      interaction.state,
      INTERACTION_STATES,
      "interaction state",
    ),
    promptSummary: readShortText(interaction.promptSummary, "promptSummary"),
    createdAt: readTimestamp(interaction.createdAt, "createdAt"),
  };
}

function readObject(payload: unknown, label: string): Record<string, unknown> {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw invalidField(label);
  }
  return payload as Record<string, unknown>;
}

function readArray(payload: unknown, label: string): unknown[] {
  if (!Array.isArray(payload) || payload.length > 100) {
    throw invalidField(label);
  }
  return payload;
}

function readShortText(payload: unknown, label: string): string {
  if (typeof payload !== "string" || payload.length < 1 || payload.length > 800) {
    throw invalidField(label);
  }
  return payload;
}

function readOptionalShortText(payload: unknown, label: string): string | null {
  if (payload === undefined || payload === null) {
    return null;
  }
  return readShortText(payload, label);
}

function readIdentifier(payload: unknown, label: string): string {
  if (
    typeof payload !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(payload)
  ) {
    throw invalidField(label);
  }
  return payload;
}

function assertSafePathIdentifier(identifier: string): void {
  readIdentifier(identifier, "path identifier");
}

function readTimestamp(payload: unknown, label: string): string {
  if (typeof payload !== "string" || Number.isNaN(Date.parse(payload))) {
    throw invalidField(label);
  }
  return payload;
}

function readInteger(payload: unknown, label: string): number {
  if (!Number.isInteger(payload)) {
    throw invalidField(label);
  }
  return payload as number;
}

function readPositiveInteger(payload: unknown, label: string): number {
  const integer = readInteger(payload, label);
  if (integer < 1) {
    throw invalidField(label);
  }
  return integer;
}

function readBoolean(payload: unknown, label: string): boolean {
  if (typeof payload !== "boolean") {
    throw invalidField(label);
  }
  return payload;
}

function readEnum<const Values extends readonly string[]>(
  payload: unknown,
  values: Values,
  label: string,
): Values[number] {
  if (typeof payload !== "string" || !values.includes(payload)) {
    throw invalidField(label);
  }
  return payload as Values[number];
}

function invalidField(label: string): WorkbenchServiceError {
  return new WorkbenchServiceError(
    "invalid_response",
    `Workbench Service returned an invalid ${label}`,
  );
}
