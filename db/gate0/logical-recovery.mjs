import {
  MigrationError,
  readMigrationAuthority,
} from "./migration-runner.mjs";

const encoder = new TextEncoder();
const ISOLATED_TARGET = Symbol("isolated-restore-target");
const CLOUD_SAFE_PREFIXES = Object.freeze([
  "workbench/design/",
  "workbench/test-report/",
  "workbench/screenshot/",
  "gate0-fixture/",
  "user-confirmed-export/",
]);

export class RecoveryError extends Error {
  constructor(code) {
    super(code);
    this.name = "RecoveryError";
    this.code = code;
  }
}

function rowsOf(result) {
  if (Array.isArray(result)) return result;
  if (Array.isArray(result?.results)) return result.results;
  return [];
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

function canonical(value) {
  return JSON.stringify(stable(value));
}

async function checksum(value) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(canonical(value))));
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function isBinary(value) {
  return value instanceof ArrayBuffer || ArrayBuffer.isView(value);
}

function tableShape(table) {
  return {
    name: table.name,
    columns: table.columns.map((column) => column.name),
    primaryKey: [...table.primaryKey],
  };
}

function rowKey(row, columns) {
  return canonical(columns.map((column) => row[column]));
}

function assertRow(contract, row) {
  if (!row || typeof row !== "object" || Array.isArray(row)) {
    throw new RecoveryError("invalid_row");
  }
  const expected = contract.columns.map((column) => column.name).sort();
  const actual = Object.keys(row).sort();
  if (canonical(expected) !== canonical(actual)) throw new RecoveryError("row_columns_mismatch");

  for (const column of contract.columns) {
    const value = row[column.name];
    if (isBinary(value)) throw new RecoveryError("blob_value_rejected");
    if (value !== null && !["string", "number", "boolean"].includes(typeof value)) {
      throw new RecoveryError("non_scalar_value_rejected");
    }
    if (value === null && !column.nullable) throw new RecoveryError("null_value_rejected");
    if (column.allowedValues && value !== null && !column.allowedValues.includes(value)) {
      throw new RecoveryError("state_value_rejected");
    }
    if (column.cloudSafeReference && (
      typeof value !== "string"
      || !CLOUD_SAFE_PREFIXES.some((prefix) => value.startsWith(prefix))
    )) {
      throw new RecoveryError("cloud_safe_reference_rejected");
    }
  }
}

function validateRows(authority, exportedTables) {
  const rowMaps = new Map();
  for (let index = 0; index < authority.tables.length; index += 1) {
    const contract = authority.tables[index];
    const exported = exportedTables[index];
    if (canonical(tableShape(contract)) !== canonical({
      name: exported?.name,
      columns: exported?.columns,
      primaryKey: exported?.primaryKey,
    })) {
      throw new RecoveryError("table_contract_mismatch");
    }
    if (!Array.isArray(exported.rows)) throw new RecoveryError("invalid_rows");

    const keys = new Map();
    for (const row of exported.rows) {
      assertRow(contract, row);
      const key = rowKey(row, contract.primaryKey);
      if (keys.has(key)) throw new RecoveryError("duplicate_primary_key");
      keys.set(key, row);
    }
    rowMaps.set(contract.name, keys);
  }

  for (let index = 0; index < authority.tables.length; index += 1) {
    const contract = authority.tables[index];
    const exported = exportedTables[index];
    for (const foreignKey of contract.foreignKeys) {
      const referencedRows = rowMaps.get(foreignKey.references.table);
      for (const row of exported.rows) {
        const values = foreignKey.columns.map((column) => row[column]);
        if (values.every((value) => value === null)) continue;
        if (values.some((value) => value === null)) throw new RecoveryError("partial_foreign_key");
        const referenceKey = canonical(values);
        const match = [...referencedRows.values()].some((candidate) => (
          canonical(foreignKey.references.columns.map((column) => candidate[column])) === referenceKey
        ));
        if (!match) throw new RecoveryError("dangling_relationship");
      }
    }
  }
}

async function assertExport(authority, exported) {
  if (exported?.format !== "lingfeng-gate0-logical-export-v2") {
    throw new RecoveryError("unsupported_export_format");
  }
  if (exported.schemaVersion !== authority.currentVersion) {
    throw new RecoveryError("schema_version_rejected");
  }
  if (!Array.isArray(exported.migrationHistory) || exported.migrationHistory.length !== authority.history.length) {
    throw new RecoveryError("migration_history_mismatch");
  }
  for (let index = 0; index < authority.history.length; index += 1) {
    const expected = authority.history[index];
    const actual = exported.migrationHistory[index];
    if (
      actual?.version !== expected.version
      || actual?.checksum !== expected.checksum
      || actual?.description !== expected.description
    ) {
      throw new RecoveryError("migration_history_mismatch");
    }
  }
  if (!Array.isArray(exported.tables) || exported.tables.length !== authority.tables.length) {
    throw new RecoveryError("table_set_mismatch");
  }

  const { checksum: supplied, ...payload } = exported;
  if (typeof supplied !== "string" || supplied !== await checksum(payload)) {
    throw new RecoveryError("checksum_rejected");
  }
  validateRows(authority, exported.tables);
}

export function createIsolatedRestoreTarget(db, metadata) {
  if (
    !db?.prepare
    || !db?.batch
    || metadata?.lifecycle !== "temporary"
    || !/^gate0-temp-[a-z0-9-]{8,80}$/u.test(metadata.databaseName || "")
  ) {
    throw new RecoveryError("isolated_target_required");
  }
  return Object.freeze({
    brand: ISOLATED_TARGET,
    db,
    databaseName: metadata.databaseName,
  });
}

export async function createLogicalExport(db, { migrations }) {
  let authority;
  try {
    authority = await readMigrationAuthority(db, migrations);
  } catch (error) {
    if (error instanceof MigrationError) throw new RecoveryError("migration_authority_rejected");
    throw error;
  }

  const statements = [
    db.prepare(
      "SELECT version, checksum, applied_at, description FROM schema_migrations ORDER BY version",
    ),
    ...authority.tables.map((contract) => {
      const order = contract.primaryKey.map((column) => `"${column}"`).join(", ");
      return db.prepare(`SELECT * FROM "${contract.name}" ORDER BY ${order}`);
    }),
  ];

  let snapshot;
  try {
    snapshot = await db.batch(statements);
  } catch {
    throw new RecoveryError("export_snapshot_failed");
  }
  const snapshotHistory = rowsOf(snapshot[0]);
  if (snapshotHistory.length !== authority.history.length) {
    throw new RecoveryError("export_snapshot_changed");
  }
  for (let index = 0; index < authority.history.length; index += 1) {
    const expected = authority.history[index];
    const actual = snapshotHistory[index];
    if (
      expected.version !== actual?.version
      || expected.checksum !== actual?.checksum
      || expected.description !== actual?.description
      || expected.applied_at !== actual?.applied_at
    ) {
      throw new RecoveryError("export_snapshot_changed");
    }
  }

  const tables = authority.tables.map((contract, index) => ({
    ...tableShape(contract),
    rows: rowsOf(snapshot[index + 1]),
  }));
  const payload = {
    format: "lingfeng-gate0-logical-export-v2",
    schemaVersion: authority.currentVersion,
    generatedAt: new Date().toISOString(),
    migrationHistory: snapshotHistory.map((row) => ({
      version: row.version,
      checksum: row.checksum,
      appliedAt: row.applied_at,
      description: row.description,
    })),
    tables,
  };
  validateRows(authority, tables);
  return Object.freeze({ ...payload, checksum: await checksum(payload) });
}

function guardName() {
  return `gate0_restore_guard_${crypto.randomUUID().replaceAll("-", "")}`;
}

function guardStatement(db, guard, expression, parameters = []) {
  return db.prepare(
    `INSERT INTO "${guard}"(ok) SELECT CASE WHEN (${expression}) THEN 1 ELSE 0 END`,
  ).bind(...parameters);
}

function postVerificationStatements(db, guard, authority, exported) {
  const statements = [];
  for (let index = 0; index < authority.tables.length; index += 1) {
    const contract = authority.tables[index];
    const table = exported.tables[index];
    statements.push(guardStatement(
      db,
      guard,
      `(SELECT COUNT(*) FROM "${contract.name}") = ?`,
      [table.rows.length],
    ));

    for (const column of contract.columns) {
      if (column.allowedValues) {
        const placeholders = column.allowedValues.map(() => "?").join(", ");
        statements.push(guardStatement(
          db,
          guard,
          `NOT EXISTS (
             SELECT 1 FROM "${contract.name}"
             WHERE "${column.name}" IS NOT NULL
               AND "${column.name}" NOT IN (${placeholders})
           )`,
          column.allowedValues,
        ));
      }
      if (column.cloudSafeReference) {
        const predicates = CLOUD_SAFE_PREFIXES.map(() => `"${column.name}" LIKE ?`).join(" OR ");
        statements.push(guardStatement(
          db,
          guard,
          `NOT EXISTS (
             SELECT 1 FROM "${contract.name}"
             WHERE "${column.name}" IS NULL OR NOT (${predicates})
           )`,
          CLOUD_SAFE_PREFIXES.map((prefix) => `${prefix}%`),
        ));
      }
    }

    for (const foreignKey of contract.foreignKeys) {
      const referenced = authority.tables.find((candidate) => candidate.name === foreignKey.references.table);
      const joins = foreignKey.columns.map((column, keyIndex) => (
        `child."${column}" = parent."${foreignKey.references.columns[keyIndex]}"`
      )).join(" AND ");
      const nonNull = foreignKey.columns.map((column) => `child."${column}" IS NOT NULL`).join(" OR ");
      const missing = referenced.primaryKey.map((column) => `parent."${column}" IS NULL`).join(" AND ");
      statements.push(guardStatement(
        db,
        guard,
        `NOT EXISTS (
           SELECT 1 FROM "${contract.name}" AS child
           LEFT JOIN "${referenced.name}" AS parent ON ${joins}
           WHERE (${nonNull}) AND (${missing})
         )`,
      ));
    }
  }
  return statements;
}

export async function restoreLogicalExport(target, exported, { migrations }) {
  if (target?.brand !== ISOLATED_TARGET) throw new RecoveryError("isolated_target_required");

  let authority;
  try {
    authority = await readMigrationAuthority(target.db, migrations);
  } catch (error) {
    if (error instanceof MigrationError) throw new RecoveryError("migration_authority_rejected");
    throw error;
  }

  await assertExport(authority, exported);

  for (const contract of authority.tables) {
    let result;
    try {
      result = await target.db.prepare(`SELECT COUNT(*) AS count FROM "${contract.name}"`).first();
    } catch {
      throw new RecoveryError("target_read_failed");
    }
    if (Number(result?.count) !== 0) throw new RecoveryError("target_not_empty");
  }

  const guard = guardName();
  const statements = [
    target.db.prepare(`CREATE TABLE "${guard}"(ok INTEGER NOT NULL CHECK(ok = 1))`),
  ];
  for (const table of exported.tables) {
    for (const row of table.rows) {
      const columns = Object.keys(row).sort();
      const columnSql = columns.map((column) => `"${column}"`).join(", ");
      const placeholders = columns.map(() => "?").join(", ");
      statements.push(
        target.db.prepare(
          `INSERT INTO "${table.name}" (${columnSql}) VALUES (${placeholders})`,
        ).bind(...columns.map((column) => row[column])),
      );
    }
  }
  statements.push(...postVerificationStatements(target.db, guard, authority, exported));
  statements.push(target.db.prepare(`DROP TABLE "${guard}"`));

  try {
    await target.db.batch(statements);
  } catch {
    throw new RecoveryError("restore_transaction_failed");
  }

  return Object.freeze({
    databaseName: target.databaseName,
    schemaVersion: exported.schemaVersion,
    checksum: exported.checksum,
    counts: Object.freeze(Object.fromEntries(
      exported.tables.map((table) => [table.name, table.rows.length]),
    )),
  });
}
