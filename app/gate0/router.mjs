import {
  Gate0AuthError,
  requireBrowserAuthorization,
  requireHermesAuthorization,
  safeAuditRecord,
} from "./auth.mjs";
import { assertTrustedSitesRuntime } from "./runtime.mjs";

function json(status, value) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export async function routeGate0(request, suppliedRuntime) {
  const runtime = assertTrustedSitesRuntime(suppliedRuntime);
  const url = new URL(request.url);

  try {
    if (url.pathname === "/gate0/browser/health") {
      if (request.method !== "GET") throw new Gate0AuthError("browser_method_rejected", 405);
      requireBrowserAuthorization(runtime.browserPrincipal, runtime.allowedBrowserSubjects);
      return json(200, { status: "ok", audience: "browser", request_id: runtime.requestId });
    }

    if (url.pathname === "/gate0/machine/health") {
      if (!["GET", "POST"].includes(request.method)) {
        throw new Gate0AuthError("hermes_method_rejected", 405);
      }
      const bodyBytes = new Uint8Array(await request.arrayBuffer());
      await requireHermesAuthorization({
        request,
        bodyBytes,
        edgePrincipal: runtime.edgePrincipal,
        expectedEdgeClientId: runtime.expectedEdgeClientId,
        expectedHost: runtime.expectedHost,
        secrets: runtime.hermesSecrets,
        nonceStore: runtime.nonceStore,
      });
      return json(200, { status: "ok", audience: "hermes", request_id: runtime.requestId });
    }

    return json(404, { error: "not_found", request_id: runtime.requestId });
  } catch (error) {
    if (!(error instanceof Gate0AuthError)) throw error;
    runtime.audit(safeAuditRecord({
      ruleId: error.ruleId,
      status: error.status,
      requestId: runtime.requestId,
    }));
    return json(error.status, { error: error.ruleId, request_id: runtime.requestId });
  }
}
