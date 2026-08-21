import {
  readBoundedObject,
  requireIdempotencyKey,
  requireSameOriginMutation,
  requireTaskApiUser,
  selectFields,
  taskActor,
  taskApiErrorResponse,
  taskApiResponse,
} from "../../../../_lib/task-bff";
import { runTaskAction } from "../../../../_lib/task-service";

const ACTIONS = new Set([
  "mark-ready",
  "start",
  "accept",
  "request-changes",
  "cancel",
  "archive",
  "restore",
]);
const ACTION_FIELDS = ["expectedVersion", "reason"] as const;
const ACCEPT_FIELDS = [
  "expectedVersion",
  "reason",
  "deliverySummary",
  "commitSha",
  "prUrl",
] as const;

type RouteContext = { params: Promise<{ taskId: string; action: string }> };

export async function POST(request: Request, context: RouteContext): Promise<Response> {
  try {
    requireSameOriginMutation(request);
    const user = await requireTaskApiUser();
    const idempotencyKey = requireIdempotencyKey(request);
    const { taskId, action } = await context.params;
    if (!ACTIONS.has(action)) return taskApiResponse({ message: "Task action is not available" }, { status: 404 });
    const fields = action === "accept" ? ACCEPT_FIELDS : ACTION_FIELDS;
    const payload = selectFields(await readBoundedObject(request), fields);
    const updated = await runTaskAction(taskId, action, idempotencyKey, {
      ...payload,
      actor: await taskActor(user),
    });
    return taskApiResponse(updated);
  } catch (error) {
    return taskApiErrorResponse(error);
  }
}
