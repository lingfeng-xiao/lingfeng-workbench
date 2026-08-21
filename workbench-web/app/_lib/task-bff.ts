import "server-only";

import { getChatGPTUser, type ChatGPTUser } from "../chatgpt-auth";
import { TaskServiceError } from "./task-service";

const MAX_REQUEST_BYTES = 64 * 1024;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;

export class TaskBffError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "TaskBffError";
  }
}

export async function requireTaskApiUser(): Promise<ChatGPTUser> {
  const user = await getChatGPTUser();
  if (!user) throw new TaskBffError(401, "Authentication is required");
  return user;
}

export async function taskActor(user: ChatGPTUser): Promise<string> {
  const bytes = new TextEncoder().encode(user.userId);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const suffix = Array.from(new Uint8Array(digest).slice(0, 12))
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  return `sites_user:${suffix}`;
}

export function requireSameOriginMutation(request: Request): void {
  const origin = request.headers.get("origin");
  if (!origin || origin !== new URL(request.url).origin) {
    throw new TaskBffError(403, "Cross-origin mutation was rejected");
  }
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin") {
    throw new TaskBffError(403, "Cross-site mutation was rejected");
  }
  if (request.headers.get("x-workbench-csrf") !== "1") {
    throw new TaskBffError(403, "CSRF confirmation is required");
  }
}

export function requireIdempotencyKey(request: Request): string {
  const key = request.headers.get("idempotency-key") ?? "";
  if (!IDEMPOTENCY_KEY_PATTERN.test(key)) {
    throw new TaskBffError(400, "A valid Idempotency-Key is required");
  }
  return key;
}

export async function readBoundedObject(request: Request): Promise<Record<string, unknown>> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new TaskBffError(415, "JSON is required");
  }
  const declaredLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_REQUEST_BYTES) {
    throw new TaskBffError(413, "Task request exceeded 64 KiB");
  }
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength > MAX_REQUEST_BYTES) {
    throw new TaskBffError(413, "Task request exceeded 64 KiB");
  }
  let payload: unknown;
  try {
    payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch {
    throw new TaskBffError(400, "Task request was not valid JSON");
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new TaskBffError(400, "Task request must be an object");
  }
  return payload as Record<string, unknown>;
}

export function selectFields(
  payload: Record<string, unknown>,
  allowedFields: readonly string[],
): Record<string, unknown> {
  const allowed = new Set(allowedFields);
  for (const field of Object.keys(payload)) {
    if (!allowed.has(field)) throw new TaskBffError(400, `Unknown field: ${field}`);
  }
  return Object.fromEntries(allowedFields.filter((field) => field in payload).map((field) => [field, payload[field]]));
}

export function taskApiResponse(payload: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("cache-control", "no-store");
  headers.set("pragma", "no-cache");
  return new Response(JSON.stringify(payload), { ...init, headers });
}

export function taskApiErrorResponse(error: unknown): Response {
  if (error instanceof TaskBffError) {
    return taskApiResponse({ message: error.message }, { status: error.status });
  }
  if (error instanceof TaskServiceError) {
    const status = {
      configuration: 503,
      access_denied: 502,
      not_found: 404,
      conflict: 409,
      unavailable: 502,
      invalid_response: 502,
    }[error.kind];
    const message = error.kind === "conflict"
      ? "数据已更新，请重新加载"
      : error.kind === "not_found"
        ? "Task 不存在"
        : "Task Service 暂时不可用";
    return taskApiResponse({ message }, { status });
  }
  return taskApiResponse({ message: "Task request failed" }, { status: 500 });
}
