import { D1NonceStore, Gate0AuthError } from "./auth.mjs";

const TRUSTED_RUNTIME = Symbol("trusted-sites-runtime");

function decodeBase64Url(value) {
  if (!/^[A-Za-z0-9_-]{43,128}$/u.test(value)) {
    throw new Gate0AuthError("hermes_key_configuration_invalid", 503);
  }
  const padded = value.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  try {
    const binary = atob(padded);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    throw new Gate0AuthError("hermes_key_configuration_invalid", 503);
  }
}

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

function parseHermesKeys(value) {
  try {
    const parsed = JSON.parse(value || "{}");
    const entries = Object.entries(parsed);
    if (entries.length === 0) throw new Error("empty");
    return new Map(entries.map(([keyId, encoded]) => {
      if (!/^[a-z0-9][a-z0-9._-]{0,63}$/u.test(keyId) || typeof encoded !== "string") {
        throw new Error("invalid");
      }
      return [keyId, decodeBase64Url(encoded)];
    }));
  } catch (error) {
    if (error instanceof Gate0AuthError) throw error;
    throw new Gate0AuthError("hermes_key_configuration_invalid", 503);
  }
}

function requireLogicalBindings(env) {
  if (!env?.DB?.prepare || !env?.ARTIFACTS?.get || !env?.ARTIFACTS?.list) {
    throw new Gate0AuthError("gate0_binding_unavailable", 503);
  }
}

export function createSitesRuntime(request, env) {
  requireLogicalBindings(env);
  if (env.SITES_HEADER_CONTRACT !== "openai-sites-v1") {
    throw new Gate0AuthError("sites_header_contract_unavailable", 503);
  }

  const authContext = request.headers.get("x-openai-sites-auth-context");
  const browserSubject = request.headers.get("x-openai-sites-user-id");
  const machineClientId = request.headers.get("x-openai-sites-machine-client-id");

  const browserPrincipal = authContext === "browser" && browserSubject
    ? Object.freeze({ kind: "sites-human", subject: browserSubject })
    : null;
  const edgePrincipal = authContext === "machine" && machineClientId
    ? Object.freeze({ kind: "sites-machine", clientId: machineClientId })
    : null;

  const requestId = crypto.randomUUID();
  const runtime = {
    brand: TRUSTED_RUNTIME,
    browserPrincipal,
    edgePrincipal,
    allowedBrowserSubjects: parseSubjectAllowlist(env.ALLOWED_BROWSER_SUBJECT_IDS),
    expectedEdgeClientId: String(env.HERMES_SITES_CLIENT_ID || ""),
    expectedHost: String(env.EXPECTED_SITE_HOST || "").toLowerCase(),
    hermesSecrets: parseHermesKeys(env.HERMES_HMAC_KEYS),
    nonceStore: new D1NonceStore(env.DB),
    requestId,
    audit(record) {
      console.info(JSON.stringify(record));
    },
  };
  return Object.freeze(runtime);
}

export function assertTrustedSitesRuntime(runtime) {
  if (runtime?.brand !== TRUSTED_RUNTIME) {
    throw new Gate0AuthError("trusted_runtime_required", 500);
  }
  return runtime;
}
