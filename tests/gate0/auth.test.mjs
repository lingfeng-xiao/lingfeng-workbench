import assert from "node:assert/strict";
import test from "node:test";

import {
  D1NonceStore,
  Gate0AuthError,
  HERMES_AUDIENCE,
  createHermesSignature,
  createIsolatedTestNonceStore,
  requireBrowserAuthorization,
  requireHermesAuthorization,
} from "../../app/gate0/auth.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const encoder = new TextEncoder();
const nowMs = 1_800_000_000_000;
const keyId = "gate0-test";
const keyBytes = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
const edgePrincipal = Object.freeze({ kind: "sites-machine", clientId: "sites-hermes-client" });

async function signed({
  url = "https://workbench.example/gate0/machine/health?b=2&a=1",
  method = "POST",
  body = "synthetic-body",
  timestamp = String(Math.floor(nowMs / 1000)),
  nonce = "synthetic_nonce_0001",
  secret = keyBytes,
} = {}) {
  const bodyBytes = encoder.encode(body);
  const unsigned = new Request(url, { method, body: method === "GET" ? undefined : body });
  const signature = await createHermesSignature({
    request: unsigned,
    bodyBytes,
    timestamp,
    nonce,
    secret,
    audience: HERMES_AUDIENCE,
  });
  const headers = new Headers({
    "x-lf-hermes-key-id": keyId,
    "x-lf-hermes-timestamp": timestamp,
    "x-lf-hermes-nonce": nonce,
    "x-lf-hermes-signature": signature,
  });
  return {
    request: new Request(url, { method, body: method === "GET" ? undefined : body, headers }),
    bodyBytes,
  };
}

function authorizationInput(candidate, overrides = {}) {
  return {
    request: candidate.request,
    bodyBytes: candidate.bodyBytes,
    edgePrincipal,
    expectedEdgeClientId: "sites-hermes-client",
    expectedHost: "workbench.example",
    secrets: new Map([[keyId, keyBytes]]),
    nonceStore: createIsolatedTestNonceStore(),
    nowMs,
    ...overrides,
  };
}

test("browser authorization accepts only a server-provided Sites human", () => {
  const allowed = new Set(["owner-subject"]);
  assert.equal(
    requireBrowserAuthorization({ kind: "sites-human", subject: "owner-subject" }, allowed).subject,
    "owner-subject",
  );
  assert.throws(
    () => requireBrowserAuthorization({ kind: "human", subject: "owner-subject" }, allowed),
    (error) => error instanceof Gate0AuthError && error.ruleId === "browser_identity_required",
  );
  assert.throws(
    () => requireBrowserAuthorization({ kind: "sites-human", subject: "other" }, allowed),
    (error) => error.ruleId === "browser_subject_forbidden",
  );
});

test("persistent D1 nonce consumption is atomic across store instances", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  db.exec(`
    CREATE TABLE gate0_machine_nonces(
      nonce_key TEXT PRIMARY KEY,
      expires_at_ms INTEGER NOT NULL,
      consumed_at_ms INTEGER NOT NULL
    )
  `);
  const first = new D1NonceStore(db);
  const second = new D1NonceStore(db);
  assert.equal(await first.consume("key:nonce", nowMs + 60_000, nowMs), true);
  assert.equal(await second.consume("key:nonce", nowMs + 60_000, nowMs), false);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM gate0_machine_nonces").first().count, 1);
});

test("Hermes requires edge identity, strong application key and one-time signature", async () => {
  const candidate = await signed();

  await assert.rejects(
    requireHermesAuthorization(authorizationInput(candidate, { edgePrincipal: null })),
    (error) => error.ruleId === "sites_machine_identity_required",
  );
  await assert.rejects(
    requireHermesAuthorization(authorizationInput(candidate, {
      secrets: new Map([[keyId, new Uint8Array(16)]]),
    })),
    (error) => error.ruleId === "hermes_weak_key_rejected",
  );

  const context = authorizationInput(candidate);
  const accepted = await requireHermesAuthorization(context);
  assert.equal(accepted.runtime, "hermes");
  await assert.rejects(
    requireHermesAuthorization(context),
    (error) => error.ruleId === "hermes_replay_rejected",
  );
});

test("signature binds method, host, path, canonical query, body and audience", async () => {
  const candidate = await signed();
  const mutations = [
    new Request("https://other.example/gate0/machine/health?b=2&a=1", {
      method: "POST",
      body: "synthetic-body",
      headers: candidate.request.headers,
    }),
    new Request("https://workbench.example/gate0/machine/other?b=2&a=1", {
      method: "POST",
      body: "synthetic-body",
      headers: candidate.request.headers,
    }),
    new Request("https://workbench.example/gate0/machine/health?b=3&a=1", {
      method: "POST",
      body: "synthetic-body",
      headers: candidate.request.headers,
    }),
    new Request("https://workbench.example/gate0/machine/health?b=2&a=1", {
      method: "POST",
      body: "mutated-body",
      headers: candidate.request.headers,
    }),
    new Request("https://workbench.example/gate0/machine/health?b=2&a=1", {
      method: "PUT",
      body: "synthetic-body",
      headers: candidate.request.headers,
    }),
  ];

  for (const request of mutations) {
    const bodyBytes = request.method === "PUT" || request.method === "POST"
      ? encoder.encode(await request.clone().text())
      : new Uint8Array();
    await assert.rejects(
      requireHermesAuthorization(authorizationInput(
        { request, bodyBytes },
        request.url.includes("other.example") ? { expectedHost: "workbench.example" } : {},
      )),
      (error) => ["hermes_host_rejected", "hermes_signature_rejected", "hermes_method_rejected"].includes(error.ruleId),
    );
  }

  await assert.rejects(
    requireHermesAuthorization(authorizationInput(candidate, { audience: "mutated-audience" })),
    (error) => error.ruleId === "hermes_signature_rejected",
  );
});

test("future and expired signatures are rejected before nonce consumption", async () => {
  const future = await signed({ timestamp: String(Math.floor((nowMs + 31_000) / 1000)) });
  await assert.rejects(
    requireHermesAuthorization(authorizationInput(future)),
    (error) => error.ruleId === "hermes_timestamp_future",
  );

  const expired = await signed({ timestamp: String(Math.floor((nowMs - 301_000) / 1000)) });
  await assert.rejects(
    requireHermesAuthorization(authorizationInput(expired)),
    (error) => error.ruleId === "hermes_timestamp_expired",
  );
});
