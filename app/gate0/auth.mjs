const encoder = new TextEncoder();
export const HERMES_AUDIENCE = "lingfeng-workbench:hermes:gate0";

export class Gate0AuthError extends Error {
  constructor(ruleId, status) {
    super(ruleId);
    this.name = "Gate0AuthError";
    this.ruleId = ruleId;
    this.status = status;
  }
}

export function requireBrowserAuthorization(principal, allowedSubjectIds) {
  if (!principal || principal.kind !== "sites-human" || typeof principal.subject !== "string") {
    throw new Gate0AuthError("browser_identity_required", 401);
  }
  if (!(allowedSubjectIds instanceof Set) || !allowedSubjectIds.has(principal.subject)) {
    throw new Gate0AuthError("browser_subject_forbidden", 403);
  }
  return Object.freeze({ kind: "human", subject: principal.subject });
}

export class D1NonceStore {
  kind = "d1-persistent";

  constructor(db) {
    if (!db?.prepare) throw new Gate0AuthError("nonce_store_unavailable", 503);
    this.db = db;
  }

  async consume(key, expiresAtMs, nowMs) {
    try {
      const inserted = await this.db.prepare(
        `INSERT INTO gate0_machine_nonces(nonce_key, expires_at_ms, consumed_at_ms)
         VALUES (?, ?, ?)
         ON CONFLICT(nonce_key) DO NOTHING
         RETURNING nonce_key`,
      ).bind(key, expiresAtMs, nowMs).first();
      return Boolean(inserted?.nonce_key);
    } catch {
      throw new Gate0AuthError("nonce_store_unavailable", 503);
    }
  }
}

export function createIsolatedTestNonceStore() {
  const entries = new Map();
  return Object.freeze({
    kind: "isolated-test",
    async consume(key, expiresAtMs, nowMs) {
      for (const [nonceKey, expiry] of entries) {
        if (expiry <= nowMs) entries.delete(nonceKey);
      }
      if (entries.has(key)) return false;
      entries.set(key, expiresAtMs);
      return true;
    },
  });
}

function base64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function constantTimeEqual(left, right) {
  const size = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < size; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

function canonicalQuery(url) {
  return [...url.searchParams.entries()]
    .sort(([leftKey, leftValue], [rightKey, rightValue]) => (
      (leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0)
      || (leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0)
    ))
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join("&");
}

async function sha256Hex(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hmac(secret, value) {
  const key = await crypto.subtle.importKey(
    "raw",
    secret,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

export async function canonicalHermesRequest({ request, bodyBytes, timestamp, nonce, audience }) {
  const url = new URL(request.url);
  const bodyHash = await sha256Hex(bodyBytes);
  return [
    audience,
    request.method.toUpperCase(),
    url.host.toLowerCase(),
    url.pathname,
    canonicalQuery(url),
    bodyHash,
    timestamp,
    nonce,
  ].join("\n");
}

export async function readBoundedRequestBody(request, maxBytes = 65_536) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) {
    throw new Gate0AuthError("hermes_body_rejected", 413);
  }
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    if (!/^\d+$/u.test(contentLength) || Number(contentLength) > maxBytes) {
      throw new Gate0AuthError("hermes_body_rejected", 413);
    }
  }
  if (!request.body) return new Uint8Array();

  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) {
        await reader.cancel();
        throw new Gate0AuthError("hermes_body_rejected", 413);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof Gate0AuthError) throw error;
    throw new Gate0AuthError("hermes_body_rejected", 413);
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

export async function createHermesSignature({
  request,
  bodyBytes,
  timestamp,
  nonce,
  secret,
  audience = HERMES_AUDIENCE,
}) {
  if (!(secret instanceof Uint8Array) || secret.byteLength < 32) {
    throw new Gate0AuthError("hermes_weak_key_rejected", 500);
  }
  return hmac(secret, await canonicalHermesRequest({ request, bodyBytes, timestamp, nonce, audience }));
}

export async function requireHermesAuthorization({
  request,
  bodyBytes = new Uint8Array(),
  edgePrincipal,
  expectedEdgeClientId,
  expectedHost,
  secrets,
  nonceStore,
  nowMs = Date.now(),
  maxAgeSeconds = 300,
  maxFutureSeconds = 30,
  audience = HERMES_AUDIENCE,
}) {
  if (
    !edgePrincipal
    || edgePrincipal.kind !== "sites-machine"
    || edgePrincipal.clientId !== expectedEdgeClientId
  ) {
    throw new Gate0AuthError("sites_machine_identity_required", 403);
  }

  const url = new URL(request.url);
  if (!expectedHost || url.host.toLowerCase() !== expectedHost.toLowerCase()) {
    throw new Gate0AuthError("hermes_host_rejected", 401);
  }
  if (!["GET", "POST"].includes(request.method.toUpperCase())) {
    throw new Gate0AuthError("hermes_method_rejected", 405);
  }
  if (!(bodyBytes instanceof Uint8Array) || bodyBytes.byteLength > 65_536) {
    throw new Gate0AuthError("hermes_body_rejected", 413);
  }

  const keyId = request.headers.get("x-lf-hermes-key-id") || "";
  const timestampText = request.headers.get("x-lf-hermes-timestamp") || "";
  const nonce = request.headers.get("x-lf-hermes-nonce") || "";
  const suppliedSignature = request.headers.get("x-lf-hermes-signature") || "";
  const secret = /^[a-z0-9][a-z0-9._-]{0,63}$/u.test(keyId) ? secrets?.get(keyId) : null;

  if (!(secret instanceof Uint8Array)) {
    throw new Gate0AuthError("hermes_key_rejected", 401);
  }
  if (secret.byteLength < 32) {
    throw new Gate0AuthError("hermes_weak_key_rejected", 503);
  }
  if (!/^\d{10,11}$/u.test(timestampText)) {
    throw new Gate0AuthError("hermes_timestamp_rejected", 401);
  }
  if (!/^[A-Za-z0-9_-]{16,96}$/u.test(nonce)) {
    throw new Gate0AuthError("hermes_nonce_rejected", 401);
  }
  if (!/^[A-Za-z0-9_-]{43}$/u.test(suppliedSignature)) {
    throw new Gate0AuthError("hermes_signature_rejected", 401);
  }

  const timestampMs = Number(timestampText) * 1000;
  if (!Number.isSafeInteger(timestampMs)) {
    throw new Gate0AuthError("hermes_timestamp_rejected", 401);
  }
  if (timestampMs - nowMs > maxFutureSeconds * 1000) {
    throw new Gate0AuthError("hermes_timestamp_future", 401);
  }
  if (nowMs - timestampMs > maxAgeSeconds * 1000) {
    throw new Gate0AuthError("hermes_timestamp_expired", 401);
  }

  const expected = await createHermesSignature({
    request,
    bodyBytes,
    timestamp: timestampText,
    nonce,
    secret,
    audience,
  });
  if (!constantTimeEqual(expected, suppliedSignature)) {
    throw new Gate0AuthError("hermes_signature_rejected", 401);
  }

  if (!nonceStore || !["d1-persistent", "isolated-test"].includes(nonceStore.kind)) {
    throw new Gate0AuthError("nonce_store_unavailable", 503);
  }
  const consumed = await nonceStore.consume(
    `${keyId}:${nonce}`,
    timestampMs + maxAgeSeconds * 1000,
    nowMs,
  );
  if (!consumed) throw new Gate0AuthError("hermes_replay_rejected", 409);

  return Object.freeze({ kind: "machine", runtime: "hermes", keyId });
}

export function safeAuditRecord({ ruleId, status, requestId }) {
  return Object.freeze({
    event: "gate0_authorization",
    rule_id: /^[a-z0-9_]{1,64}$/u.test(ruleId) ? ruleId : "authorization_error",
    status: Number.isInteger(status) ? status : 500,
    request_id: /^[0-9a-f-]{36}$/u.test(requestId) ? requestId : crypto.randomUUID(),
  });
}
