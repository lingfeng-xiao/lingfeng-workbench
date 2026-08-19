import assert from "node:assert/strict";
import test from "node:test";

import {
  Gate0AuthError,
  MemoryNonceStore,
  createHermesSignature,
  requireBrowserAuthorization,
  requireHermesAuthorization,
} from "../../app/gate0/auth.mjs";
import { routeGate0 } from "../../app/gate0/router.mjs";

const nowMs = 1_800_000_000_000;
const timestamp = String(Math.floor(nowMs / 1000));
const nonce = "synthetic_nonce_0001";
const keyId = "gate0-test";
const keyBytes = new Uint8Array([83, 121, 110, 116, 104, 101, 116, 105, 99, 45, 75, 101, 121, 45, 48, 49]);

async function signedRequest(overrides = {}) {
  const request = new Request("https://example.invalid/gate0/machine/health", {
    method: "POST",
    body: "",
  });
  const signature = await createHermesSignature({
    request,
    bodyBytes: new Uint8Array(),
    timestamp,
    nonce,
    secret: keyBytes,
  });
  const headers = new Headers({
    "x-lf-hermes-key-id": keyId,
    "x-lf-hermes-timestamp": timestamp,
    "x-lf-hermes-nonce": nonce,
    "x-lf-hermes-signature": signature,
    ...overrides,
  });
  return new Request(request, { headers });
}

function machineContext(overrides = {}) {
  return {
    edgeIdentityAsserted: true,
    secrets: new Map([[keyId, keyBytes]]),
    nonceStore: new MemoryNonceStore(),
    nowMs,
    ...overrides,
  };
}

test("browser authorization is server-side and subject-scoped", () => {
  const allowed = new Set(["owner-subject"]);
  assert.equal(requireBrowserAuthorization({ kind: "human", subject: "owner-subject" }, allowed).subject, "owner-subject");
  assert.throws(
    () => requireBrowserAuthorization(null, allowed),
    (error) => error instanceof Gate0AuthError && error.ruleId === "browser_identity_required",
  );
  assert.throws(
    () => requireBrowserAuthorization({ kind: "human", subject: "someone-else" }, allowed),
    (error) => error instanceof Gate0AuthError && error.ruleId === "browser_subject_forbidden",
  );
});

test("Hermes requires both edge identity and application HMAC", async () => {
  const request = await signedRequest();

  await assert.rejects(
    requireHermesAuthorization({ request, bodyBytes: new Uint8Array(), ...machineContext({ edgeIdentityAsserted: false }) }),
    (error) => error.ruleId === "sites_machine_identity_required",
  );

  const edgeOnly = new Request(request.url, { method: "POST" });
  await assert.rejects(
    requireHermesAuthorization({ request: edgeOnly, bodyBytes: new Uint8Array(), ...machineContext() }),
    (error) => error.ruleId === "hermes_key_rejected",
  );

  const wrong = await signedRequest({ "x-lf-hermes-signature": "A".repeat(43) });
  await assert.rejects(
    requireHermesAuthorization({ request: wrong, bodyBytes: new Uint8Array(), ...machineContext() }),
    (error) => error.ruleId === "hermes_signature_rejected",
  );

  const accepted = await requireHermesAuthorization({
    request,
    bodyBytes: new Uint8Array(),
    ...machineContext(),
  });
  assert.equal(accepted.runtime, "hermes");
});

test("Hermes expiry and nonce replay are rejected", async () => {
  const request = await signedRequest();
  await assert.rejects(
    requireHermesAuthorization({
      request,
      bodyBytes: new Uint8Array(),
      ...machineContext({ nowMs: nowMs + 301_000 }),
    }),
    (error) => error.ruleId === "hermes_timestamp_expired",
  );

  const context = machineContext();
  await requireHermesAuthorization({ request, bodyBytes: new Uint8Array(), ...context });
  await assert.rejects(
    requireHermesAuthorization({ request, bodyBytes: new Uint8Array(), ...context }),
    (error) => error.ruleId === "hermes_replay_rejected",
  );
});

test("machine route emits sanitized denial evidence", async () => {
  const audit = [];
  const response = await routeGate0(
    new Request("https://example.invalid/gate0/machine/health", { method: "POST" }),
    {
      requestId: "request-1",
      edgeMachineIdentityAsserted: true,
      hermesSecrets: new Map([[keyId, keyBytes]]),
      nonceStore: new MemoryNonceStore(),
      nowMs,
      audit: (event) => audit.push(event),
    },
  );
  assert.equal(response.status, 401);
  assert.deepEqual(audit, [{
    event: "gate0_authorization",
    rule_id: "hermes_key_rejected",
    status: 401,
    request_id: "request-1",
  }]);
  assert.doesNotMatch(JSON.stringify(audit), /x-lf-hermes|synthetic_nonce|A{43}/iu);
});
