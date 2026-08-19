import assert from "node:assert/strict";
import test from "node:test";

import { HERMES_AUDIENCE, createHermesSignature } from "../../app/gate0/auth.mjs";
import { routeGate0 } from "../../app/gate0/router.mjs";
import { createSitesRuntime } from "../../app/gate0/runtime.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const key = Uint8Array.from({ length: 32 }, (_, index) => index + 11);
function encoded(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}
function env(db) {
  return {
    DB: db,
    ARTIFACTS: { get() {}, list() {} },
    SITES_HEADER_CONTRACT: "openai-sites-v1",
    ALLOWED_BROWSER_SUBJECT_IDS: JSON.stringify(["owner-subject"]),
    HERMES_SITES_CLIENT_ID: "sites-hermes-client",
    HERMES_HMAC_KEYS: JSON.stringify({ "gate0-test": encoded(key) }),
    EXPECTED_SITE_HOST: "workbench.example",
  };
}
function nonceTable(db) {
  db.exec("CREATE TABLE gate0_machine_nonces(nonce_key TEXT PRIMARY KEY, expires_at_ms INTEGER NOT NULL, consumed_at_ms INTEGER NOT NULL)");
}

test("runtime maps only trusted Sites browser headers and owns request IDs", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  nonceTable(db);
  const request = new Request("https://workbench.example/gate0/browser/health", {
    headers: {
      "oai-authenticated-user-id": "owner-subject",
      "x-request-id": "caller-controlled",
    },
  });
  const runtime = createSitesRuntime(request, env(db));
  assert.deepEqual(runtime.browserPrincipal, { kind: "sites-human", subject: "owner-subject" });
  assert.equal(runtime.edgePrincipal, null);
  const response = await routeGate0(request, runtime);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.match(body.request_id, /^[0-9a-f-]{36}$/u);
  assert.notEqual(body.request_id, "caller-controlled");
});

test("legacy x-openai browser headers cannot create a browser principal", (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  nonceTable(db);
  const request = new Request("https://workbench.example/gate0/browser/health", {
    headers: {
      "x-openai-sites-auth-context": "browser",
      "x-openai-sites-user-id": "owner-subject",
    },
  });
  assert.equal(createSitesRuntime(request, env(db)).browserPrincipal, null);
});

test("edge assertion cannot be injected through booleans or unrelated headers", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  nonceTable(db);
  const request = new Request("https://workbench.example/gate0/machine/health", {
    headers: {
      "x-edge-identity-asserted": "true",
      "x-openai-sites-machine-client-id": "sites-hermes-client",
    },
  });
  assert.equal(createSitesRuntime(request, env(db)).edgePrincipal, null);
  await assert.rejects(
    routeGate0(request, { edgeIdentityAsserted: true }),
    (error) => error.ruleId === "trusted_runtime_required",
  );
});

test("machine runtime consumes nonce through the D1 binding", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  nonceTable(db);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const url = "https://workbench.example/gate0/machine/health?probe=gate0";
  const unsigned = new Request(url, { method: "POST", body: "probe" });
  const signature = await createHermesSignature({
    request: unsigned,
    bodyBytes: new TextEncoder().encode("probe"),
    timestamp,
    nonce: "runtime_nonce_000001",
    secret: key,
    audience: HERMES_AUDIENCE,
  });
  const request = new Request(url, {
    method: "POST",
    body: "probe",
    headers: {
      "x-openai-sites-auth-context": "machine",
      "x-openai-sites-machine-client-id": "sites-hermes-client",
      "x-lf-hermes-key-id": "gate0-test",
      "x-lf-hermes-timestamp": timestamp,
      "x-lf-hermes-nonce": "runtime_nonce_000001",
      "x-lf-hermes-signature": signature,
    },
  });
  const first = await routeGate0(request.clone(), createSitesRuntime(request.clone(), env(db)));
  const replay = await routeGate0(request.clone(), createSitesRuntime(request.clone(), env(db)));
  assert.equal(first.status, 200);
  assert.equal(replay.status, 409);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM gate0_machine_nonces").first().count, 1);
});

test("runtime fails closed without trusted contract or D1/R2 bindings", () => {
  const db = new SqliteD1();
  const request = new Request("https://workbench.example/gate0/browser/health");
  const missingContract = env(db);
  delete missingContract.SITES_HEADER_CONTRACT;
  assert.throws(() => createSitesRuntime(request, missingContract), (error) => error.ruleId === "sites_header_contract_unavailable");
  const missingR2 = env(db);
  delete missingR2.ARTIFACTS;
  assert.throws(() => createSitesRuntime(request, missingR2), (error) => error.ruleId === "gate0_binding_unavailable");
  db.close();
});
