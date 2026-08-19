import assert from "node:assert/strict";
import test from "node:test";

import {
  RecoveryError,
  createLogicalExport,
  restoreLogicalExport,
} from "../../db/gate0/logical-recovery.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const specs = [
  { name: "fixture_parent", primaryKey: ["id"] },
  { name: "fixture_child", primaryKey: ["id"] },
  { name: "fixture_idempotency", primaryKey: ["id"] },
  { name: "fixture_blob_ref", primaryKey: ["id"] },
];

function createSchema(db) {
  db.exec(`
    CREATE TABLE fixture_parent(id TEXT PRIMARY KEY, label TEXT NOT NULL);
    CREATE TABLE fixture_child(
      id TEXT PRIMARY KEY,
      parent_id TEXT NOT NULL REFERENCES fixture_parent(id),
      state TEXT NOT NULL CHECK(state IN ('queued', 'done'))
    );
    CREATE TABLE fixture_idempotency(id TEXT PRIMARY KEY, result TEXT NOT NULL);
    CREATE TABLE fixture_blob_ref(id TEXT PRIMARY KEY, object_key TEXT NOT NULL);
  `);
}

function populate(db, { dangling = false, objectKey = "gate0-fixture/report.json" } = {}) {
  db.prepare("INSERT INTO fixture_parent(id, label) VALUES (?, ?)").bind("p-1", "synthetic").run();
  if (dangling) db.exec("PRAGMA foreign_keys = OFF");
  db.prepare("INSERT INTO fixture_child(id, parent_id, state) VALUES (?, ?, ?)")
    .bind("c-1", dangling ? "missing" : "p-1", "queued").run();
  db.prepare("INSERT INTO fixture_idempotency(id, result) VALUES (?, ?)")
    .bind("idem-1", "accepted").run();
  db.prepare("INSERT INTO fixture_blob_ref(id, object_key) VALUES (?, ?)")
    .bind("blob-1", objectKey).run();
}

async function verifyRestored(db) {
  const foreignKeys = db.prepare("PRAGMA foreign_key_check").all().results;
  const invalidStates = db.prepare(
    "SELECT COUNT(*) AS count FROM fixture_child WHERE state NOT IN ('queued', 'done')",
  ).first().count;
  const invalidRefs = db.prepare(
    "SELECT COUNT(*) AS count FROM fixture_blob_ref WHERE object_key NOT LIKE 'gate0-fixture/%'",
  ).first().count;
  return {
    ok: foreignKeys.length === 0 && Number(invalidStates) === 0 && Number(invalidRefs) === 0,
    foreign_key_violations: foreignKeys.length,
    invalid_states: Number(invalidStates),
    invalid_blob_refs: Number(invalidRefs),
  };
}

test("logical export restores real rows into a new isolated database", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  createSchema(source);
  createSchema(restored);
  populate(source);

  const exported = await createLogicalExport(source, {
    tableSpecs: specs,
    schemaVersion: "0001_fixture_state",
    generatedAt: "2026-08-19T00:00:00.000Z",
  });
  const result = await restoreLogicalExport(
    { db: restored, isIsolated: true },
    exported,
    {
      tableSpecs: specs,
      allowedSchemaVersions: new Set(["0001_fixture_state"]),
      validateExport: async (candidate) => ({
        ok: candidate.tables.every((table) => table.rows.every((row) => (
          table.name !== "fixture_blob_ref" || row.object_key.startsWith("gate0-fixture/")
        ))),
      }),
      verify: verifyRestored,
    },
  );

  assert.equal(result.checksum.length, 64);
  assert.deepEqual(result.counts, {
    fixture_parent: 1,
    fixture_child: 1,
    fixture_idempotency: 1,
    fixture_blob_ref: 1,
  });
  assert.equal(result.verification.ok, true);
});

test("corrupt, incompatible, online and non-empty restores are rejected", async (t) => {
  const source = new SqliteD1();
  t.after(() => source.close());
  createSchema(source);
  populate(source);
  const exported = await createLogicalExport(source, {
    tableSpecs: specs,
    schemaVersion: "0001_fixture_state",
  });

  const corruptTarget = new SqliteD1();
  const incompatibleTarget = new SqliteD1();
  const onlineTarget = new SqliteD1();
  const nonEmptyTarget = new SqliteD1();
  t.after(() => corruptTarget.close());
  t.after(() => incompatibleTarget.close());
  t.after(() => onlineTarget.close());
  t.after(() => nonEmptyTarget.close());
  for (const db of [corruptTarget, incompatibleTarget, onlineTarget, nonEmptyTarget]) createSchema(db);
  nonEmptyTarget.prepare("INSERT INTO fixture_parent(id, label) VALUES (?, ?)").bind("existing", "keep").run();

  await assert.rejects(
    restoreLogicalExport(
      { db: corruptTarget, isIsolated: true },
      { ...exported, generatedAt: "changed" },
      { tableSpecs: specs, allowedSchemaVersions: new Set(["0001_fixture_state"]) },
    ),
    (error) => error instanceof RecoveryError && error.code === "checksum_rejected",
  );
  await assert.rejects(
    restoreLogicalExport(
      { db: incompatibleTarget, isIsolated: true },
      exported,
      { tableSpecs: specs, allowedSchemaVersions: new Set(["9999_other"]) },
    ),
    (error) => error.code === "schema_version_rejected",
  );
  await assert.rejects(
    restoreLogicalExport(
      { db: onlineTarget, isIsolated: false },
      exported,
      { tableSpecs: specs, allowedSchemaVersions: new Set(["0001_fixture_state"]) },
    ),
    (error) => error.code === "online_target_forbidden",
  );
  await assert.rejects(
    restoreLogicalExport(
      { db: nonEmptyTarget, isIsolated: true },
      exported,
      { tableSpecs: specs, allowedSchemaVersions: new Set(["0001_fixture_state"]) },
    ),
    (error) => error.code === "target_not_empty",
  );
  assert.equal(nonEmptyTarget.prepare("SELECT label FROM fixture_parent WHERE id = ?").bind("existing").first().label, "keep");
});

test("invalid cloud-safe reference is rejected before any write", async (t) => {
  const source = new SqliteD1();
  const target = new SqliteD1();
  t.after(() => source.close());
  t.after(() => target.close());
  createSchema(source);
  createSchema(target);
  populate(source, { objectKey: "not-allowed/report.json" });
  const exported = await createLogicalExport(source, {
    tableSpecs: specs,
    schemaVersion: "0001_fixture_state",
  });

  await assert.rejects(
    restoreLogicalExport(
      { db: target, isIsolated: true },
      exported,
      {
        tableSpecs: specs,
        allowedSchemaVersions: new Set(["0001_fixture_state"]),
        validateExport: async (candidate) => ({
          ok: candidate.tables.every((table) => table.rows.every((row) => (
            table.name !== "fixture_blob_ref" || row.object_key.startsWith("gate0-fixture/")
          ))),
        }),
      },
    ),
    (error) => error.code === "export_preflight_failed",
  );
  assert.equal(target.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});

test("dangling relationships roll back the isolated restore batch", async (t) => {
  const source = new SqliteD1();
  const target = new SqliteD1();
  t.after(() => source.close());
  t.after(() => target.close());
  createSchema(source);
  createSchema(target);
  populate(source, { dangling: true });
  const exported = await createLogicalExport(source, {
    tableSpecs: specs,
    schemaVersion: "0001_fixture_state",
  });

  await assert.rejects(
    restoreLogicalExport(
      { db: target, isIsolated: true },
      exported,
      { tableSpecs: specs, allowedSchemaVersions: new Set(["0001_fixture_state"]) },
    ),
    (error) => error.code === "restore_batch_failed",
  );
  assert.equal(target.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});
