const encoder = new TextEncoder();

export class Gate0AuthError extends Error {
  constructor(ruleId, status) {
    super(ruleId);
    this.name = "Gate0AuthError";
    this.ruleId = ruleId;
    this.status = status;
  }
}

export function requireBrowserAuthorization(principal, allowedSubjectIds) {
  if (!principal || principal.kind !== "human" || typeof principal.subject !== "string") {
    throw new Gate0AuthError("browser_identity_required", 401);
  }
  if (!(allowedSubjectIds instanceof Set) || !allowedSubjectIds.has(principal.subject)) {
    throw new Gate0AuthError("browser_subject_forbidden", 403);
  }
  return Object.freeze({ kind: "human", subject: principal.subject });
}

export class MemoryNonceStore {
  #entries = new Map();

  consume(key, expiresAtMs, nowMs = Date.now()) {
    for (const [nonceKey, expiry] of this.#entries) {
      if (expiry <= nowMs) this.#entries.delete(nonceKey);
    }
    if (this.#entries.has(key)) return false;
    this.#entries.set(key, expiresAtMs);
    return true;
  }
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

async function sha256Hex(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hmac(secret, value) {
  const keyBytes = typeof secret === "string" ? encoder.encode(secret) : secret;
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

async function canonicalRequest(request, bodyBytes, timestamp, nonce) {
  const url = new URL(request.url);
  const bodyHash = await sha256Hex(bodyBytes);
  return [request.method.toUpperCase(), url.pathname, bodyHash, timestamp, nonce].join("\n");
}

export async function createHermesSignature({ request, bodyBytes, timestamp, nonce, secret }) {
  return hmac(secret, await canonicalRequest(request, bodyBytes, timestamp, nonce));
}

export async function requireHermesAuthorization({
  request,
  bodyBytes = new Uint8Array(),
  edgeIdentityAsserted,
  secrets,
  nonceStore,
  nowMs = Date.now(),
  maxClockSkewSeconds = 300,
}) {
  if (edgeIdentityAsserted !== true) {
    throw new Gate0AuthError("sites_machine_identity_required", 403);
  }

  const keyId = request.headers.get("x-lf-hermes-key-id") || "";
  const timestampText = request.headers.get("x-lf-hermes-timestamp") || "";
  const nonce = request.headers.get("x-lf-hermes-nonce") || "";
  const suppliedSignature = request.headers.get("x-lf-hermes-signature") || "";

  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/u.test(keyId) || !secrets?.has(keyId)) {
    throw new Gate0AuthError("hermes_key_rejected", 401);
  }
  if (!/^\d{10}$/u.test(timestampText)) {
    throw new Gate0AuthError("hermes_timestamp_rejected", 401);
  }
  if (!/^[A-Za-z0-9_-]{16,96}$/u.test(nonce)) {
    throw new Gate0AuthError("hermes_nonce_rejected", 401);
  }
  if (!/^[A-Za-z0-9_-]{43}$/u.test(suppliedSignature)) {
    throw new Gate0AuthError("hermes_signature_rejected", 401);
  }

  const timestampMs = Number(timestampText) * 1000;
  const skewMs = maxClockSkewSeconds * 1000;
  if (!Number.isSafeInteger(timestampMs) || Math.abs(nowMs - timestampMs) > skewMs) {
    throw new Gate0AuthError("hermes_timestamp_expired", 401);
  }

  const expected = await createHermesSignature({
    request,
    bodyBytes,
    timestamp: timestampText,
    nonce,
    secret: secrets.get(keyId),
  });
  if (!constantTimeEqual(expected, suppliedSignature)) {
    throw new Gate0AuthError("hermes_signature_rejected", 401);
  }

  if (!nonceStore?.consume(`${keyId}:${nonce}`, timestampMs + skewMs, nowMs)) {
    throw new Gate0AuthError("hermes_replay_rejected", 409);
  }
  return Object.freeze({ kind: "machine", runtime: "hermes", keyId });
}

export function safeAuditRecord({ ruleId, status, requestId }) {
  return Object.freeze({
    event: "gate0_authorization",
    rule_id: String(ruleId),
    status: Number(status),
    request_id: String(requestId || ""),
  });
}
