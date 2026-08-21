import {
  readBoundedObject,
  requireIdempotencyKey,
  requireSameOriginMutation,
  requireTaskApiUser,
  selectFields,
  taskActor,
  taskApiErrorResponse,
  taskApiResponse,
} from "../../../_lib/task-bff";
import { pollTask, updateTask } from "../../../_lib/task-service";

const UPDATE_FIELDS = [
  "expectedVersion",
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
  "reason",
] as const;

type RouteContext = { params: Promise<{ taskId: string }> };

export async function GET(request: Request, context: RouteContext): Promise<Response> {
  try {
    await requireTaskApiUser();
    const { taskId } = await context.params;
    const polled = await pollTask(taskId, request.headers.get("if-none-match"));
    if (polled.notModified) {
      return new Response(null, {
        status: 304,
        headers: {
          "cache-control": "no-store",
          pragma: "no-cache",
          ...(polled.etag ? { etag: polled.etag } : {}),
        },
      });
    }
    return taskApiResponse(polled.task, {
      headers: polled.etag ? { etag: polled.etag } : undefined,
    });
  } catch (error) {
    return taskApiErrorResponse(error);
  }
}

export async function PUT(request: Request, context: RouteContext): Promise<Response> {
  try {
    requireSameOriginMutation(request);
    const user = await requireTaskApiUser();
    const idempotencyKey = requireIdempotencyKey(request);
    const { taskId } = await context.params;
    const payload = selectFields(await readBoundedObject(request), UPDATE_FIELDS);
    const reason = payload.reason;
    delete payload.reason;
    const updated = await updateTask(taskId, idempotencyKey, {
      ...payload,
      actor: await taskActor(user),
      reason,
    });
    return taskApiResponse(updated);
  } catch (error) {
    return taskApiErrorResponse(error);
  }
}
