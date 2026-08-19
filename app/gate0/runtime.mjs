import { Gate0AuthError } from "./auth.mjs";

const TRUSTED_RUNTIME = Symbol("trusted-sites-runtime");

function parseSubjectAllowlist(value) {
  try {
    const parsed = JSON.parse(value || "[]");
    if (!Array.isArray(parsed) || parsed.some((subject) => (
      typeof subject !== "string" || !/^[A-Za-z0-9._:-]{3,128}$/u.test(subject)
    ))) {
      throw new Error("invalid");
    }
    return new Set(parsed);
  } catch {
    throw new Gate0AuthError("browser_allowlist_configuration_invalid", 503);
  }
}

function requireLogicalBindings(env) {
  if (!env?.DB?.prepare || !env?.ARTIFACTS?.get || !env?.ARTIFACTS?.list) {
    throw new Gate0AuthError("gate0_binding_unavailable", 503);
  }
}

export function createSitesRuntime(request, env) {
  requireLogicalBindings(env);
  const browserSubject = request.headers.get("oai-authenticated-user-id");
  const requestId = crypto.randomUUID();
  return Object.freeze({
    brand: TRUSTED_RUNTIME,
    browserPrincipal: browserSubject
      ? Object.freeze({ kind: "sites-human", subject: browserSubject })
      : null,
    machineEdgeAvailable: false,
    allowedBrowserSubjects: parseSubjectAllowlist(env.ALLOWED_BROWSER_SUBJECT_IDS),
    requestId,
    audit(record) {
      console.info(JSON.stringify(record));
    },
  });
}

export function assertTrustedSitesRuntime(runtime) {
  if (runtime?.brand !== TRUSTED_RUNTIME) {
    throw new Gate0AuthError("trusted_runtime_required", 500);
  }
  return runtime;
}
