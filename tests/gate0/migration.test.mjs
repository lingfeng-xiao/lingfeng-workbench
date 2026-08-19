import assert from "node:assert/strict";
import test from "node:test";

import {
  MigrationError,
  migrate,
  prepareMigrationPlan,
} from "../../db/gate0/migration-runner.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const oldContract = {
  tables: [
    {
      name: "fixture_parent",
      columns: [
        { name: "id" },
        { name: "label" },
      ],
      primaryKey: ["id"],
    },
    {
      name: "fixture_child",
      columns: [
        { name: "id" },
        { name: "parent_id" },
        { name: "value" },
      ],
      primaryKey: ["id"],
      foreignKeys: [{
        columns: ["parent_id"],
        references: { table: "fixture_parent", columns: ["id"] },
      }],
    },
  ],
};

const currentContract = {
  tables: [
    oldContract.tables[0],
    {
      ...oldContract.tables[1],
      columns: [
        ...oldContract.tables[1].columns,
        { name: "state", allowedValues: ["queued", "done"] },
      ],
    },
  ],
};

const baseline = {
  version: "0000_gate0",
  description: "Synthetic Gate 0 baseline",
  sql: `
    CREATE TABLE fixture_parent (
      id TEXT PRIMARY KEY,
      label TEXT NOT NULL
    )
    -- gate0:statement
    CREATE TABLE fixture_child (
      id TEXT PRIMARY KEY,
      parent_id TEXT NOT NULL REFERENCES fixture_parent(id),
      value TEXT NOT NULL
    )
  `,
  exportContract: oldContract,
};

const upgrade = {
  version: "0001_fixture_state",
  description: "Add a synthetic state column",
  sql: `
    ALTER TABLE fixture_child ADD COLUMN state TEXT NOT NULL DEFAULT 'queued'
    -- gate0:statement
    CREATE INDEX fixture_child_parent_idx ON fixture_child(parent_id)
  `,
  exportContract: currentContract,
};

test("empty database migrates and replay is idempotent", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());

  const first = await migrate(db, [baseline, upgrade], { now: () => "2026-08-19T00:00:00.000Z" });
  assert.deepEqual(first.appliedNow, ["0000_gate0", "0001_fixture_state"]);

  const replay = await migrate(db, [baseline, upgrade]);
  assert.deepEqual(replay.appliedNow, []);
  assert.equal(replay.history.length, 2);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM fixture_child").first().count, 0);
});

test("synthetic old version upgrades without losing relationships or state", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());

  await migrate(db, [baseline]);
  db.prepare("INSERT INTO fixture_parent(id, label) VALUES (?, ?)").bind("parent-1", "kept").run();
  db.prepare("INSERT INTO fixture_child(id, parent_id, value) VALUES (?, ?, ?)")
    .bind("child-1", "parent-1", "kept").run();

  await migrate(db, [baseline, upgrade]);
  const row = db.prepare("SELECT parent_id, value, state FROM fixture_child WHERE id = ?").bind("child-1").first();
  assert.equal(row.parent_id, "parent-1");
  assert.equal(row.value, "kept");
  assert.equal(row.state, "queued");
  assert.deepEqual(db.prepare("PRAGMA foreign_key_check").all().results, []);
});

test("duplicate ordinal, duplicate name and gaps are rejected before SQL", async () => {
  const secondOrdinalZero = {
    ...baseline,
    version: "0000_other",
    description: "Other synthetic migration",
  };
  await assert.rejects(
    prepareMigrationPlan([baseline, secondOrdinalZero]),
    (error) => error instanceof MigrationError && error.code === "duplicate_ordinal",
  );

  const duplicateName = {
    ...upgrade,
    version: "0001_gate0",
    description: "Duplicate synthetic name",
  };
  await assert.rejects(
    prepareMigrationPlan([baseline, duplicateName]),
    (error) => error.code === "duplicate_name",
  );
  await assert.rejects(
    prepareMigrationPlan([upgrade]),
    (error) => error.code === "migration_gap",
  );
});

test("unknown, checksum-tampered and description-tampered histories are rejected", async (t) => {
  const unknown = new SqliteD1();
  const checksumTampered = new SqliteD1();
  const descriptionTampered = new SqliteD1();
  t.after(() => unknown.close());
  t.after(() => checksumTampered.close());
  t.after(() => descriptionTampered.close());

  await migrate(unknown, [baseline]);
  unknown.prepare(
    "INSERT INTO schema_migrations(version, checksum, applied_at, description) VALUES (?, ?, ?, ?)",
  ).bind("9999_unknown", "0".repeat(64), "2026-08-19T00:00:00Z", "synthetic").run();
  await assert.rejects(
    migrate(unknown, [baseline, upgrade]),
    (error) => error.code === "unknown_applied_migration",
  );

  await migrate(checksumTampered, [baseline]);
  checksumTampered.prepare("UPDATE schema_migrations SET checksum = ? WHERE version = ?")
    .bind("f".repeat(64), "0000_gate0").run();
  await assert.rejects(
    migrate(checksumTampered, [baseline]),
    (error) => error.code === "applied_migration_tampered",
  );

  await migrate(descriptionTampered, [baseline]);
  descriptionTampered.prepare("UPDATE schema_migrations SET description = ? WHERE version = ?")
    .bind("changed description", "0000_gate0").run();
  await assert.rejects(
    migrate(descriptionTampered, [baseline]),
    (error) => error.code === "applied_migration_description_tampered",
  );
});

test("failed migration is atomic, unmarked and reports only a sanitized class", async (t) => {
  const db = new SqliteD1();
  t.after(() => db.close());
  await migrate(db, [baseline]);

  const broken = {
    version: "0001_broken",
    description: "Synthetic negative control",
    sql: `
      CREATE TABLE fixture_partial(id TEXT PRIMARY KEY)
      -- gate0:statement
      INSERT INTO table_that_does_not_exist(id) VALUES ('synthetic-sensitive-marker')
    `,
    exportContract: currentContract,
  };

  await assert.rejects(
    migrate(db, [baseline, broken]),
    (error) => error.code === "migration_failed"
      && error.message === "migration_failed"
      && !error.message.includes("synthetic-sensitive-marker"),
  );
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'table' AND name = 'fixture_partial'").first().count,
    0,
  );
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM schema_migrations").first().count, 1);
});
