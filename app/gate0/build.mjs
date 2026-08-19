import { copyFile, mkdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../..");
const output = resolve(root, "dist");
const server = resolve(output, "server");
const drizzle = resolve(output, ".openai/drizzle");

await rm(output, { recursive: true, force: true });
await mkdir(server, { recursive: true });
await mkdir(resolve(output, "public"), { recursive: true });
await mkdir(resolve(output, ".openai"), { recursive: true });
await mkdir(drizzle, { recursive: true });

const copies = [
  [resolve(here, "server.mjs"), resolve(server, "index.js")],
  [resolve(here, "auth.mjs"), resolve(server, "auth.mjs")],
  [resolve(here, "router.mjs"), resolve(server, "router.mjs")],
  [resolve(here, "runtime.mjs"), resolve(server, "runtime.mjs")],
  [resolve(root, "db/gate0/migration-runner.mjs"), resolve(server, "migration-runner.mjs")],
  [resolve(root, "db/gate0/logical-recovery.mjs"), resolve(server, "logical-recovery.mjs")],
  [resolve(here, "index.html"), resolve(output, "public/index.html")],
  [resolve(root, ".openai/hosting.json"), resolve(output, ".openai/hosting.json")],
  [resolve(here, "bindings.json"), resolve(output, ".openai/bindings.json")],
  [
    resolve(root, "migrations/gate0/0000_gate0_runtime.sql"),
    resolve(drizzle, "0000_gate0_runtime.sql"),
  ],
];
for (const [source, destination] of copies) await copyFile(source, destination);

console.log("Gate 0 Sites artifact built at dist/; no version was saved or deployed.");
