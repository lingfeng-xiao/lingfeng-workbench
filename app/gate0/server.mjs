import * as Gate0Auth from "./auth.mjs";
import * as Gate0Migration from "./migration-runner.mjs";
import * as Gate0Recovery from "./logical-recovery.mjs";
import { routeGate0 } from "./router.mjs";
import { createSitesRuntime } from "./runtime.mjs";

export const gate0Contracts = Object.freeze({
  auth: Gate0Auth,
  migration: Gate0Migration,
  recovery: Gate0Recovery,
});

function safeFailure(error) {
  const requestId = crypto.randomUUID();
  const known = error instanceof Gate0Auth.Gate0AuthError;
  const status = known ? error.status : 500;
  const ruleId = known ? error.ruleId : "internal_error";
  console.info(JSON.stringify(Gate0Auth.safeAuditRecord({ ruleId, status, requestId })));
  return new Response(JSON.stringify({ error: ruleId, request_id: requestId }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export default {
  async fetch(request, env) {
    try {
      return await routeGate0(request, createSitesRuntime(request, env));
    } catch (error) {
      return safeFailure(error);
    }
  },
};
