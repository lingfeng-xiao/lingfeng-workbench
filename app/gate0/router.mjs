import {
  Gate0AuthError,
  requireBrowserAuthorization,
  requireHermesAuthorization,
  safeAuditRecord,
} from "./auth.mjs";

function json(status, value) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export async function routeGate0(request, context) {
  const url = new URL(request.url);
  const requestId = context.requestId || crypto.randomUUID();

  try {
    if (url.pathname === "/gate0/browser/health") {
      requireBrowserAuthorization(context.browserPrincipal, context.allowedBrowserSubjects);
      return json(200, { status: "ok", audience: "browser", request_id: requestId });
    }

    if (url.pathname === "/gate0/machine/health") {
      const bodyBytes = new Uint8Array(await request.arrayBuffer());
      await requireHermesAuthorization({
        request,
        bodyBytes,
        edgeIdentityAsserted: context.edgeMachineIdentityAsserted,
        secrets: context.hermesSecrets,
        nonceStore: context.nonceStore,
        nowMs: context.nowMs,
      });
      return json(200, { status: "ok", audience: "hermes", request_id: requestId });
    }

    return json(404, { error: "not_found", request_id: requestId });
  } catch (error) {
    if (!(error instanceof Gate0AuthError)) throw error;
    context.audit?.(safeAuditRecord({
      ruleId: error.ruleId,
      status: error.status,
      requestId,
    }));
    return json(error.status, { error: error.ruleId, request_id: requestId });
  }
}
