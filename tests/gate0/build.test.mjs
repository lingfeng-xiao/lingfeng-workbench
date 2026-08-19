import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { promisify } from "node:util";
import test from "node:test";

const execute = promisify(execFile);

test("build emits a runnable Sites server artifact and logical binding manifest", async () => {
  await execute(process.execPath, ["app/gate0/build.mjs"], { cwd: process.cwd() });

  const [entry, sourceHosting, artifactHosting, bindings] = await Promise.all([
    readFile("dist/server/index.js", "utf8"),
    readFile(".openai/hosting.json", "utf8"),
    readFile("dist/.openai/hosting.json", "utf8"),
    readFile("dist/.openai/bindings.json", "utf8"),
  ]);

  assert.match(entry, /from "\.\/auth\.mjs"/u);
  assert.match(entry, /from "\.\/router\.mjs"/u);
  assert.match(entry, /from "\.\/migration-runner\.mjs"/u);
  assert.match(entry, /from "\.\/logical-recovery\.mjs"/u);
  assert.match(entry, /async fetch\(request, env\)/u);
  assert.deepEqual(JSON.parse(artifactHosting), JSON.parse(sourceHosting));

  const manifest = JSON.parse(bindings);
  assert.equal(manifest.logical_bindings.D1.name, "DB");
  assert.equal(manifest.logical_bindings.R2.name, "ARTIFACTS");
  assert.match(
    manifest.runtime_tables.gate0_machine_nonces.join(" "),
    /nonce_key TEXT PRIMARY KEY/u,
  );
});
