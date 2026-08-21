import "server-only";

import {
  assertTaskId,
  parseCreatedTask,
  parseStartedTask,
  parseTaskDetail,
  parseTaskList,
  TaskContractError,
  type CreatedTask,
  type StartedTask,
  type TaskDetail,
  type TaskSummary,
} from "./task-contracts";

const TASK_API_PREFIX = "/api/tasks/v1";
const MAX_MESSAGE_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 5_000;

export type TaskServiceErrorKind =
  | "configuration"
  | "access_denied"
  | "not_found"
  | "conflict"
  | "unavailable"
  | "invalid_response";

export class TaskServiceError extends Error {
  constructor(
    public readonly kind: TaskServiceErrorKind,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "TaskServiceError";
  }
}

export async function listTasks(query = ""): Promise<TaskSummary[]> {
  const response = await requestTaskService(`/tasks${query}`, "read");
  return parse(response.payload, parseTaskList);
}

export async function getTask(taskId: string): Promise<TaskDetail> {
  assertSafeTaskId(taskId);
  const response = await requestTaskService(`/tasks/${encodeURIComponent(taskId)}`, "read");
  return parse(response.payload, parseTaskDetail);
}

export async function pollTask(
  taskId: string,
  etag: string | null,
): Promise<{ notModified: boolean; task: TaskDetail | null; etag: string | null }> {
  assertSafeTaskId(taskId);
  const response = await requestTaskService(
    `/tasks/${encodeURIComponent(taskId)}`,
    "read",
    { etag },
  );
  if (response.notModified) {
    return { notModified: true, task: null, etag: response.etag };
  }
  return {
    notModified: false,
    task: parse(response.payload, parseTaskDetail),
    etag: response.etag,
  };
}

export async function createTask(
  idempotencyKey: string,
  request: unknown,
): Promise<CreatedTask> {
  const response = await requestTaskService("/tasks", "write", {
    method: "POST",
    idempotencyKey,
    request,
  });
  return parse(response.payload, parseCreatedTask);
}

export async function updateTask(
  taskId: string,
  idempotencyKey: string,
  request: unknown,
): Promise<TaskDetail> {
  assertSafeTaskId(taskId);
  const response = await requestTaskService(
    `/tasks/${encodeURIComponent(taskId)}`,
    "write",
    { method: "PUT", idempotencyKey, request },
  );
  return parse(response.payload, parseTaskDetail);
}

export async function runTaskAction(
  taskId: string,
  action: string,
  idempotencyKey: string,
  request: unknown,
): Promise<TaskDetail | StartedTask> {
  assertSafeTaskId(taskId);
  if (![
    "mark-ready",
    "start",
    "accept",
    "request-changes",
    "cancel",
    "archive",
    "restore",
  ].includes(action)) {
    throw new TaskServiceError("not_found", "Task action is not available");
  }
  const response = await requestTaskService(
    `/tasks/${encodeURIComponent(taskId)}/${action}`,
    "write",
    { method: "POST", idempotencyKey, request },
  );
  return action === "start"
    ? parse(response.payload, parseStartedTask)
    : parse(response.payload, parseTaskDetail);
}

async function requestTaskService(
  path: string,
  tokenKind: "read" | "write",
  options: {
    method?: "POST" | "PUT";
    idempotencyKey?: string;
    request?: unknown;
    etag?: string | null;
  } = {},
): Promise<{ payload: unknown; etag: string | null; notModified: boolean }> {
  const configuration = readConfiguration();
  const headers = new Headers({
    Accept: "application/json",
    Authorization: `Bearer ${
      tokenKind === "read" ? configuration.readToken : configuration.writeToken
    }`,
  });
  let body: Uint8Array | undefined;
  if (options.method) {
    if (!options.idempotencyKey) {
      throw new TaskServiceError("invalid_response", "Idempotency-Key is required");
    }
    headers.set("Content-Type", "application/json");
    headers.set("Idempotency-Key", options.idempotencyKey);
    body = new TextEncoder().encode(JSON.stringify(options.request));
    if (body.byteLength > MAX_MESSAGE_BYTES) {
      throw new TaskServiceError("invalid_response", "Task request exceeded 64 KiB");
    }
  }
  if (options.etag) headers.set("If-None-Match", options.etag);

  let response: Response;
  try {
    response = await fetch(`${configuration.taskApiBaseUrl}${path}`, {
      method: options.method ?? "GET",
      headers,
      body,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(configuration.timeoutMs),
    });
  } catch (error) {
    throw new TaskServiceError("unavailable", "Task Service request failed", {
      cause: error,
    });
  }
  const responseEtag = response.headers.get("etag");
  if (response.status === 304) {
    return { payload: null, etag: responseEtag ?? options.etag ?? null, notModified: true };
  }
  if (response.status === 401 || response.status === 403) {
    throw new TaskServiceError("access_denied", "Task Service denied the request");
  }
  if (response.status === 404) {
    throw new TaskServiceError("not_found", "Task was not found");
  }
  if (response.status === 409) {
    throw new TaskServiceError("conflict", await boundedErrorMessage(response));
  }
  if (!response.ok) {
    throw new TaskServiceError("unavailable", `Task Service returned ${response.status}`);
  }
  return {
    payload: await boundedJson(response),
    etag: responseEtag,
    notModified: false,
  };
}

async function boundedErrorMessage(response: Response): Promise<string> {
  try {
    const payload = await boundedJson(response);
    if (
      payload &&
      typeof payload === "object" &&
      !Array.isArray(payload) &&
      typeof (payload as Record<string, unknown>).message === "string"
    ) {
      const message = (payload as Record<string, string>).message;
      return message.length <= 800 ? message : "Task changed while this form was open";
    }
  } catch {
    // A malformed error response is mapped to the same bounded conflict message.
  }
  return "Task changed while this form was open";
}

async function boundedJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new TaskServiceError("invalid_response", "Task Service returned non-JSON");
  }
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_MESSAGE_BYTES) {
    await response.body?.cancel();
    throw new TaskServiceError("invalid_response", "Task Service response exceeded 64 KiB");
  }
  if (!response.body) {
    throw new TaskServiceError("invalid_response", "Task Service returned an empty response");
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > MAX_MESSAGE_BYTES) {
      await reader.cancel();
      throw new TaskServiceError("invalid_response", "Task Service response exceeded 64 KiB");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch (error) {
    throw new TaskServiceError("invalid_response", "Task Service returned invalid JSON", {
      cause: error,
    });
  }
}

function readConfiguration(): {
  taskApiBaseUrl: string;
  readToken: string;
  writeToken: string;
  timeoutMs: number;
} {
  const baseUrl = process.env.WORKBENCH_SERVICE_BASE_URL?.trim();
  const readToken = process.env.WORKBENCH_SERVICE_READ_TOKEN?.trim();
  const writeToken = process.env.WORKBENCH_SERVICE_WRITE_TOKEN?.trim();
  if (!baseUrl || !readToken || !writeToken) {
    throw new TaskServiceError(
      "configuration",
      "Task Service URL and separate read/write credentials are required",
    );
  }
  let serviceUrl: URL;
  try {
    serviceUrl = new URL(baseUrl);
  } catch (error) {
    throw new TaskServiceError("configuration", "Task Service URL is invalid", {
      cause: error,
    });
  }
  const local =
    serviceUrl.protocol === "http:" &&
    ["127.0.0.1", "localhost"].includes(serviceUrl.hostname);
  if (serviceUrl.protocol !== "https:" && !local) {
    throw new TaskServiceError("configuration", "Task Service must use HTTPS");
  }
  if (serviceUrl.username || serviceUrl.password || serviceUrl.search || serviceUrl.hash) {
    throw new TaskServiceError("configuration", "Task Service URL contains forbidden parts");
  }
  const configuredTimeout = Number(process.env.WORKBENCH_SERVICE_TIMEOUT_MS);
  const timeoutMs = Number.isInteger(configuredTimeout)
    ? Math.min(Math.max(configuredTimeout, 100), 30_000)
    : DEFAULT_TIMEOUT_MS;
  return {
    taskApiBaseUrl: `${serviceUrl.toString().replace(/\/$/, "")}${TASK_API_PREFIX}`,
    readToken,
    writeToken,
    timeoutMs,
  };
}

function assertSafeTaskId(taskId: string): void {
  try {
    assertTaskId(taskId);
  } catch (error) {
    throw new TaskServiceError("not_found", "Task identifier is invalid", { cause: error });
  }
}

function parse<T>(payload: unknown, parser: (value: unknown) => T): T {
  try {
    return parser(payload);
  } catch (error) {
    if (error instanceof TaskContractError) {
      throw new TaskServiceError("invalid_response", "Task Service response violated contract", {
        cause: error,
      });
    }
    throw error;
  }
}
