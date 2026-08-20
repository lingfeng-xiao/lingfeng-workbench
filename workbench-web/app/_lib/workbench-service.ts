import "server-only";
import {
  assertWorkItemPathId,
  ContractParseError,
  parseInteractionList,
  parseNodeList,
  parseWorkItemDetail,
  parseWorkItemList,
  type InteractionSummary,
  type NodeSummary,
  type WorkItemDetail,
  type WorkItemSummary,
} from "./contracts";

export type {
  InteractionSummary,
  MissionProjection,
  NodeSummary,
  NotificationProjection,
  RunProjection,
  TimelineEvent,
  WorkItemDetail,
  WorkItemSummary,
} from "./contracts";

const CLIENT_API_PREFIX = "/api/client/v2";
const MAX_RESPONSE_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 5_000;
const MAX_TIMEOUT_MS = 30_000;

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
  return parseResponse(await requestServiceJson("/work-items?limit=50"), parseWorkItemList);
}

export async function getWorkItem(workItemId: string): Promise<WorkItemDetail> {
  try {
    assertWorkItemPathId(workItemId);
  } catch (error) {
    throw invalidResponse("WorkItem path identifier is invalid", error);
  }
  const payload = await requestServiceJson(
    `/work-items/${encodeURIComponent(workItemId)}`,
  );
  return parseResponse(payload, parseWorkItemDetail);
}

export async function listNodes(): Promise<NodeSummary[]> {
  return parseResponse(await requestServiceJson("/nodes"), parseNodeList);
}

export async function listInteractions(): Promise<InteractionSummary[]> {
  return parseResponse(
    await requestServiceJson("/interactions?limit=100"),
    parseInteractionList,
  );
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

  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw invalidResponse("Workbench Service returned a non-JSON response");
  }

  const contentLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > MAX_RESPONSE_BYTES) {
    await response.body?.cancel();
    throw invalidResponse("Workbench Service response exceeded 64 KiB");
  }

  const responseBytes = await readBoundedResponse(response);
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(responseBytes)) as unknown;
  } catch (error) {
    throw invalidResponse("Workbench Service returned invalid JSON", error);
  }
}

async function readBoundedResponse(response: Response): Promise<Uint8Array> {
  if (!response.body) {
    throw invalidResponse("Workbench Service returned an empty response");
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw invalidResponse("Workbench Service response exceeded 64 KiB");
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof WorkbenchServiceError) throw error;
    throw invalidResponse("Workbench Service response could not be read", error);
  } finally {
    reader.releaseLock();
  }

  const responseBytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    responseBytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return responseBytes;
}

function parseResponse<T>(payload: unknown, parser: (value: unknown) => T): T {
  try {
    return parser(payload);
  } catch (error) {
    if (error instanceof ContractParseError) {
      throw invalidResponse("Workbench Service response violated the v2 contract", error);
    }
    throw error;
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
  if (serviceUrl.username || serviceUrl.password || serviceUrl.search || serviceUrl.hash) {
    throw new WorkbenchServiceError(
      "configuration",
      "Workbench Service base URL must not contain credentials, query, or fragment",
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

function invalidResponse(message: string, cause?: unknown): WorkbenchServiceError {
  return new WorkbenchServiceError("invalid_response", message, { cause });
}
