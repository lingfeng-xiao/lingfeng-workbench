import {
  readBoundedObject,
  requireIdempotencyKey,
  requireSameOriginMutation,
  requireTaskApiUser,
  selectFields,
  taskActor,
  taskApiErrorResponse,
  taskApiResponse,
} from "../../_lib/task-bff";
import { createTask, listTasks } from "../../_lib/task-service";

const CREATE_FIELDS = [
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
  "dataBoundaryAcknowledged",
  "reason",
] as const;

export async function GET(request: Request): Promise<Response> {
  try {
    await requireTaskApiUser();
    const source = new URL(request.url).searchParams;
    const forwarded = new URLSearchParams();
    for (const key of ["businessStatus", "attentionState", "targetNodeId", "includeArchived", "limit"]) {
      const value = source.get(key);
      if (value !== null) forwarded.set(key, value);
    }
    const query = forwarded.size > 0 ? `?${forwarded.toString()}` : "";
    return taskApiResponse(await listTasks(query));
  } catch (error) {
    return taskApiErrorResponse(error);
  }
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOriginMutation(request);
    const user = await requireTaskApiUser();
    const idempotencyKey = requireIdempotencyKey(request);
    const payload = selectFields(await readBoundedObject(request), CREATE_FIELDS);
    if (payload.dataBoundaryAcknowledged !== true) {
      return taskApiResponse({ message: "必须确认本地数据边界" }, { status: 400 });
    }
    const reason = payload.reason;
    delete payload.reason;
    delete payload.dataBoundaryAcknowledged;
    const created = await createTask(idempotencyKey, {
      ...payload,
      dataBoundaryAcknowledged: true,
      actor: await taskActor(user),
      reason,
    });
    return taskApiResponse(created, { status: 201 });
  } catch (error) {
    return taskApiErrorResponse(error);
  }
}
