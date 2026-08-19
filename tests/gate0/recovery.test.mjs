import assert from "node:assert/strict";
import test from "node:test";

import {
  RecoveryError,
  createIsolatedRestoreTarget,
  createLogicalExport,
  restoreLogicalExport,
} from "../../db/gate0/logical-recovery.mjs";
import { migrate } from "../../db/gate0/migration-runner.mjs";
import { SqliteD1 } from "./sqlite-d1.mjs";

const encoder = new TextEncoder();
const migrations = [{
  version: "0000_gate0",
  description: "Synthetic recovery authority",
  sql: `
    CREATE TABLE fixture_parent(id TEXT PRIMARY KEY, label TEXT NOT NULL)
    -- gate0:statement
    CREATE TABLE fixture_child(
      id TEXT PRIMARY KEY,
      parent_id TEXT NOT NULL REFERENCES fixture_parent(id),
      state TEXT NOT NULL
    )
    -- gate0:statement
    CREATE TABLE fixture_idempotency(id TEXT PRIMARY KEY, result TEXT NOT NULL)
    -- gate0:statement
    CREATE TABLE fixture_blob_ref(id TEXT PRIMARY KEY, object_key TEXT NOT NULL)
  `,
  exportContract: {
    tables: [
      {
        name: "fixture_parent",
        columns: [{ name: "id" }, { name: "label" }],
        primaryKey: ["id"],
      },
      {
        name: "fixture_child",
        columns: [
          { name: "id" },
          { name: "parent_id" },
          { name: "state", allowedValues: ["queued", "done"] },
        ],
        primaryKey: ["id"],
        foreignKeys: [{
          columns: ["parent_id"],
          references: { table: "fixture_parent", columns: ["id"] },
        }],
      },
      {
        name: "fixture_idempotency",
        columns: [{ name: "id" }, { name: "result" }],
        primaryKey: ["id"],
      },
      {
        name: "fixture_blob_ref",
        columns: [
          { name: "id" },
          { name: "object_key", cloudSafeReference: true },
        ],
        primaryKey: ["id"],
      },
    ],
  },
}];

async function setup(db) {
  await migrate(db, migrations, { now: () => "2026-08-19T00:00:00.000Z" });
}

function populate(db, objectKey = "gate0-fixture/report.json") {
  db.prepare("INSERT INTO fixture_parent(id, label) VALUES (?, ?)").bind("p-1", "synthetic").run();
  db.prepare("INSERT INTO fixture_child(id, parent_id, state) VALUES (?, ?, ?)")
    .bind("c-1", "p-1", "queued").run();
  db.prepare("INSERT INTO fixture_idempotency(id, result) VALUES (?, ?)")
    .bind("idem-1", "accepted").run();
  db.prepare("INSERT INTO fixture_blob_ref(id, object_key) VALUES (?, ?)")
    .bind("blob-1", objectKey).run();
}

async function isolationTokenHash(token) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(token)));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function provisionIsolation(db, suffix = "12345678") {
  db.exec(`
    CREATE TABLE IF NOT EXISTS gate0_restore_isolation_attestations (
      token_hash TEXT PRIMARY KEY,
      database_name TEXT NOT NULL,
      purpose TEXT NOT NULL,
      expires_at_ms INTEGER NOT NULL,
      consumed_at_ms INTEGER
    )
  `);
  const token = `gate0_isolation_${suffix}_0123456789abcdef`;
  const nowMs = 1_800_000_000_000;
  db.prepare(
    `INSERT INTO gate0_restore_isolation_attestations
     (token_hash, database_name, purpose, expires_at_ms, consumed_at_ms)
     VALUES (?, ?, ?, ?, NULL)`,
  ).bind(
    await isolationTokenHash(token),
    `gate0-temp-${suffix}`,
    "gate0-isolated-restore",
    nowMs + 60_000,
  ).run();
  return { token, nowMs };
}

async function target(db, suffix = "12345678") {
  return createIsolatedRestoreTarget(db, await provisionIsolation(db, suffix));
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

async function rechecksum(exported) {
  const { checksum: ignored, ...payload } = exported;
  const bytes = new Uint8Array(await crypto.subtle.digest(
    "SHA-256",
    encoder.encode(JSON.stringify(stable(payload))),
  ));
  return {
    ...payload,
    checksum: [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join(""),
  };
}

test("schema version, table plan and rows come from checksum-bound migration authority", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  await setup(source);
  await setup(restored);
  populate(source);

  const exported = await createLogicalExport(source, { migrations });
  assert.equal(exported.schemaVersion, "0000_gate0");
  assert.deepEqual(exported.tables.map((table) => table.name), [
    "fixture_parent",
    "fixture_child",
    "fixture_idempotency",
    "fixture_blob_ref",
  ]);
  assert.equal(exported.migrationHistory[0].description, "Synthetic recovery authority");

  const result = await restoreLogicalExport(await target(restored), exported, { migrations });
  assert.equal(result.checksum.length, 64);
  assert.deepEqual(result.counts, {
    fixture_parent: 1,
    fixture_child: 1,
    fixture_idempotency: 1,
    fixture_blob_ref: 1,
  });
  assert.deepEqual(restored.prepare("PRAGMA foreign_key_check").all().results, []);
});

test("restore rejects forged target capability, schema and table plan before writes", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  await setup(source);
  await setup(restored);
  populate(source);
  const exported = await createLogicalExport(source, { migrations });

  await assert.rejects(
    restoreLogicalExport({ db: restored, isIsolated: true }, exported, { migrations }),
    (error) => error instanceof RecoveryError && error.code === "isolated_target_required",
  );

  await assert.rejects(
    restoreLogicalExport(await target(restored, "schema0001"), { ...exported, schemaVersion: "9999_other" }, { migrations }),
    (error) => error.code === "schema_version_rejected",
  );

  const missingTable = await rechecksum({ ...exported, tables: exported.tables.slice(0, -1) });
  await assert.rejects(
    restoreLogicalExport(await target(restored, "tables0001"), missingTable, { migrations }),
    (error) => error.code === "table_set_mismatch",
  );
  assert.equal(restored.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});

test("checksum, cloud-safe reference and non-empty target are rejected before writes", async (t) => {
  const source = new SqliteD1();
  const corruptTarget = new SqliteD1();
  const referenceTarget = new SqliteD1();
  const nonEmptyTarget = new SqliteD1();
  t.after(() => source.close());
  t.after(() => corruptTarget.close());
  t.after(() => referenceTarget.close());
  t.after(() => nonEmptyTarget.close());
  for (const db of [source, corruptTarget, referenceTarget, nonEmptyTarget]) await setup(db);
  populate(source);
  const exported = await createLogicalExport(source, { migrations });

  await assert.rejects(
    restoreLogicalExport(
      await target(corruptTarget, "corrupt01"),
      { ...exported, generatedAt: "changed" },
      { migrations },
    ),
    (error) => error.code === "checksum_rejected",
  );

  const badReferenceTables = exported.tables.map((table) => (
    table.name === "fixture_blob_ref"
      ? { ...table, rows: [{ ...table.rows[0], object_key: "company-data/report.json" }] }
      : table
  ));
  const badReference = await rechecksum({ ...exported, tables: badReferenceTables });
  await assert.rejects(
    restoreLogicalExport(await target(referenceTarget, "badref001"), badReference, { migrations }),
    (error) => error.code === "cloud_safe_reference_rejected",
  );

  nonEmptyTarget.prepare("INSERT INTO fixture_parent(id, label) VALUES (?, ?)")
    .bind("existing", "keep").run();
  await assert.rejects(
    restoreLogicalExport(await target(nonEmptyTarget, "nonempty1"), exported, { migrations }),
    (error) => error.code === "target_not_empty",
  );
  assert.equal(
    nonEmptyTarget.prepare("SELECT label FROM fixture_parent WHERE id = ?").bind("existing").first().label,
    "keep",
  );
  assert.equal(referenceTarget.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});

test("BLOB values are explicitly rejected at the export boundary", async (t) => {
  const source = new SqliteD1();
  t.after(() => source.close());
  await setup(source);
  populate(source);
  source.prepare("UPDATE fixture_parent SET label = ? WHERE id = ?")
    .bind(new Uint8Array([1, 2, 3]), "p-1").run();

  await assert.rejects(
    createLogicalExport(source, { migrations }),
    (error) => error.code === "blob_value_rejected",
  );
});

test("post-write state verification runs in the same batch and rolls every row back", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  await setup(source);
  await setup(restored);
  populate(source);
  const exported = await createLogicalExport(source, { migrations });

  restored.exec(`
    CREATE TRIGGER mutate_fixture_label
    AFTER INSERT ON fixture_parent
    BEGIN
      UPDATE fixture_parent SET label = 'another-valid-label' WHERE id = NEW.id;
    END;
  `);

  await assert.rejects(
    restoreLogicalExport(await target(restored, "postcheck"), exported, { migrations }),
    (error) => error.code === "restore_transaction_failed",
  );

  for (const table of ["fixture_parent", "fixture_child", "fixture_idempotency", "fixture_blob_ref"]) {
    assert.equal(restored.prepare(`SELECT COUNT(*) AS count FROM "${table}"`).first().count, 0);
  }
  assert.equal(
    restored.prepare(
      "SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'table' AND name LIKE 'gate0_restore_guard_%'",
    ).first().count,
    0,
  );
});

test("target migration history description tamper blocks restore authority", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  await setup(source);
  await setup(restored);
  populate(source);
  const exported = await createLogicalExport(source, { migrations });
  restored.prepare("UPDATE schema_migrations SET description = ?").bind("tampered").run();

  await assert.rejects(
    restoreLogicalExport(await target(restored, "authority"), exported, { migrations }),
    (error) => error.code === "migration_authority_rejected",
  );
  assert.equal(restored.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});

test("export aborts when migration authority changes before the read snapshot", async (t) => {
  const source = new SqliteD1();
  t.after(() => source.close());
  await setup(source);
  populate(source);

  let mutated = false;
  const changingSource = {
    prepare: (sql) => source.prepare(sql),
    batch: async (statements) => {
      if (!mutated) {
        mutated = true;
        source.prepare("UPDATE schema_migrations SET description = ?")
          .bind("changed-during-export").run();
      }
      return source.batch(statements);
    },
  };

  await assert.rejects(
    createLogicalExport(changingSource, { migrations }),
    (error) => error.code === "export_snapshot_changed",
  );
});

test("production-like databases cannot forge or replay restore isolation", async (t) => {
  const productionLike = new SqliteD1();
  const isolated = new SqliteD1();
  t.after(() => productionLike.close());
  t.after(() => isolated.close());
  await setup(productionLike);
  await setup(isolated);

  await assert.rejects(
    createIsolatedRestoreTarget(productionLike, {
      token: "forged_isolation_token_0123456789",
      nowMs: 1_800_000_000_000,
      lifecycle: "temporary",
      databaseName: "gate0-temp-forged001",
    }),
    (error) => error.code === "isolated_target_required",
  );

  const attestation = await provisionIsolation(isolated, "replay001");
  await createIsolatedRestoreTarget(isolated, attestation);
  await assert.rejects(
    createIsolatedRestoreTarget(isolated, attestation),
    (error) => error.code === "isolated_target_required",
  );
});

test("restore rejects non-finite and unsafe numeric values before writes", async (t) => {
  const source = new SqliteD1();
  const restored = new SqliteD1();
  t.after(() => source.close());
  t.after(() => restored.close());
  await setup(source);
  await setup(restored);
  populate(source);
  const exported = await createLogicalExport(source, { migrations });
  const isolatedTarget = await target(restored, "numbers01");

  for (const unsafe of [Number.NaN, Number.POSITIVE_INFINITY, 9_007_199_254_740_992, 1.5]) {
    const tables = exported.tables.map((table) => (
      table.name === "fixture_parent"
        ? { ...table, rows: [{ ...table.rows[0], label: unsafe }] }
        : table
    ));
    const candidate = await rechecksum({ ...exported, tables });
    await assert.rejects(
      restoreLogicalExport(isolatedTarget, candidate, { migrations }),
      (error) => error.code === "unsafe_number_rejected",
    );
  }
  assert.equal(restored.prepare("SELECT COUNT(*) AS count FROM fixture_parent").first().count, 0);
});
