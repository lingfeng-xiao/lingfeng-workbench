import assert from "node:assert/strict";
import test from "node:test";

import { HERMES_AUDIENCE, createHermesSignature } from "../../app/gate0/auth.mjs";
import { routeGate0 } from "../../app/gate0/router.mjs";
import { createSitesRuntime } from "../../app/gate0/runtime.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const key = Uint8Array.from({ length: 32 }, (_, index) => index + 11);

function env(db) {
  return {
    DB: db,
    ARTIFACTS: { get() {}, list() {} },
    ALLOWED_BROWSER_SUBJECT_IDS: JSON.stringify(["owner-subject"]),
  };
}

function nonceTable(db) {
  db.exec("CREATE TABLE gate0_machine_nonces(nonce_key TEXT PRIMARY KEY, expires_at_ms INTEGER NOT NULL, consumed_at_ms INTEGER NOT NULL)");
}

test("runtime maps only the official Sites browser identity and owns request IDs", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  const request = new Request("https://workbench.example/gate0/browser/health", {
    headers: {
      "oai-authenticated-user-id": "owner-subject",
      "x-request-id": "caller-controlled",
    },
  });
  const runtime = createSitesRuntime(request, env(db));
  assert.deepEqual(runtime.browserPrincipal, { kind: "sites-human", subject: "owner-subject" });
  assert.equal(runtime.machineEdgeAvailable, false);
  const response = await routeGate0(request, runtime);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.match(body.request_id, /^[0-9a-f-]{36}$/u);
  assert.notEqual(body.request_id, "caller-controlled");
});

test("unofficial identity headers and environment flags cannot enable the machine route", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  nonceTable(db);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const url = "https://workbench.example/gate0/machine/health";
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
  const configured = {
    ...env(db),
    SITES_HEADER_CONTRACT: "openai-sites-v1",
    MACHINE_EDGE_AVAILABLE: "true",
    HERMES_SITES_CLIENT_ID: "sites-hermes-client",
  };
  const runtime = createSitesRuntime(request, configured);
  assert.equal(runtime.machineEdgeAvailable, false);
  const response = await routeGate0(request, runtime);
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "machine_edge_contract_unavailable");
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM gate0_machine_nonces").first().count, 0);
});

test("runtime fails closed without D1 or R2 while browser route needs no machine secret", async () => {
  const db = new SqliteD1();
  const request = new Request("https://workbench.example/gate0/browser/health", {
    headers: { "oai-authenticated-user-id": "owner-subject" },
  });
  const response = await routeGate0(request, createSitesRuntime(request, env(db)));
  assert.equal(response.status, 200);
  const missingR2 = env(db);
  delete missingR2.ARTIFACTS;
  assert.throws(
    () => createSitesRuntime(request, missingR2),
    (error) => error.ruleId === "gate0_binding_unavailable",
  );
  db.close();
});
