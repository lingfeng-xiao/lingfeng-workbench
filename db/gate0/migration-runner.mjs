const encoder = new TextEncoder();
const VERSION_PATTERN = /^(\d{4})_([a-z][a-z0-9_]*)$/u;
const IDENTIFIER = /^[a-z][a-z0-9_]{0,62}$/u;
const STATEMENT_MARKER = /^\s*--\s*gate0:statement\s*$/imu;
const AUTHORITY = Symbol("migration-authority");

export class MigrationError extends Error {
  constructor(code) {
    super(code);
    this.name = "MigrationError";
    this.code = code;
  }
}

function rowsOf(result) {
  if (Array.isArray(result)) return result;
  if (Array.isArray(result?.results)) return result.results;
  return [];
}

function identifier(value, errorCode) {
  if (!IDENTIFIER.test(value || "")) throw new MigrationError(errorCode);
  return value;
}

function normalizeColumn(column) {
  const name = identifier(column?.name, "invalid_contract_column");
  const allowedValues = column.allowedValues === undefined
    ? null
    : [...column.allowedValues];
  if (allowedValues && (
    allowedValues.length === 0
    || allowedValues.some((value) => !["string", "number", "boolean"].includes(typeof value))
  )) {
    throw new MigrationError("invalid_allowed_values");
  }
  if (column.storage && column.storage !== "scalar" && column.storage !== "blob-rejected") {
    throw new MigrationError("invalid_column_storage");
  }
  return Object.freeze({
    name,
    nullable: column.nullable === true,
    storage: column.storage || "scalar",
    allowedValues: allowedValues ? Object.freeze(allowedValues) : null,
    cloudSafeReference: column.cloudSafeReference === true,
  });
}

function normalizeExportContract(contract) {
  if (!contract || !Array.isArray(contract.tables) || contract.tables.length === 0) {
    throw new MigrationError("missing_export_contract");
  }
  const seenTables = new Set();
  const tables = contract.tables.map((table) => {
    const name = identifier(table?.name, "invalid_contract_table");
    if (seenTables.has(name)) throw new MigrationError("duplicate_contract_table");
    seenTables.add(name);

    if (!Array.isArray(table.columns) || table.columns.length === 0) {
      throw new MigrationError("missing_contract_columns");
    }
    const columns = table.columns.map(normalizeColumn);
    const columnNames = new Set();
    for (const column of columns) {
      if (columnNames.has(column.name)) throw new MigrationError("duplicate_contract_column");
      columnNames.add(column.name);
    }

    if (!Array.isArray(table.primaryKey) || table.primaryKey.length === 0) {
      throw new MigrationError("missing_contract_primary_key");
    }
    const primaryKey = table.primaryKey.map((column) => identifier(column, "invalid_contract_primary_key"));
    if (new Set(primaryKey).size !== primaryKey.length || primaryKey.some((column) => !columnNames.has(column))) {
      throw new MigrationError("invalid_contract_primary_key");
    }

    const foreignKeys = (table.foreignKeys || []).map((foreignKey) => {
      const columnsInKey = foreignKey.columns.map((column) => identifier(column, "invalid_contract_foreign_key"));
      const referenceTable = identifier(
        foreignKey.references?.table,
        "invalid_contract_foreign_key",
      );
      const referenceColumns = foreignKey.references?.columns?.map(
        (column) => identifier(column, "invalid_contract_foreign_key"),
      ) || [];
      if (
        columnsInKey.length === 0
        || columnsInKey.length !== referenceColumns.length
        || columnsInKey.some((column) => !columnNames.has(column))
      ) {
        throw new MigrationError("invalid_contract_foreign_key");
      }
      return Object.freeze({
        columns: Object.freeze(columnsInKey),
        references: Object.freeze({
          table: referenceTable,
          columns: Object.freeze(referenceColumns),
        }),
      });
    });

    return Object.freeze({
      name,
      columns: Object.freeze(columns),
      primaryKey: Object.freeze(primaryKey),
      foreignKeys: Object.freeze(foreignKeys),
    });
  });

  const tableMap = new Map(tables.map((table) => [table.name, table]));
  for (const table of tables) {
    for (const foreignKey of table.foreignKeys) {
      const referenced = tableMap.get(foreignKey.references.table);
      if (!referenced || foreignKey.references.columns.some(
        (column) => !referenced.columns.some((candidate) => candidate.name === column),
      )) {
        throw new MigrationError("invalid_contract_foreign_key");
      }
    }
  }
  return Object.freeze({ tables: Object.freeze(tables) });
}

function normalizeStatement(statement) {
  const normalized = statement.trim().replace(/;\s*$/u, "");
  if (!normalized) throw new MigrationError("empty_statement");
  if (normalized.includes(";")) throw new MigrationError("multiple_statements_without_marker");
  if (/^(begin|commit|rollback|savepoint|release)\b/iu.test(normalized)) {
    throw new MigrationError("transaction_control_forbidden");
  }
  if (/\bschema_migrations\b/iu.test(normalized)) {
    throw new MigrationError("migration_metadata_write_forbidden");
  }
  return normalized;
}

export function parseMigrationSql(sql) {
  if (typeof sql !== "string" || !sql.trim()) throw new MigrationError("empty_migration");
  return sql.split(STATEMENT_MARKER).map((part) => part.trim()).filter(Boolean).map(normalizeStatement);
}

function normalizeMigration(migration) {
  const match = VERSION_PATTERN.exec(migration.version || "");
  if (!match) throw new MigrationError("invalid_version");
  if (typeof migration.description !== "string" || !migration.description.trim()) {
    throw new MigrationError("missing_description");
  }
  return Object.freeze({
    version: migration.version,
    ordinal: Number(match[1]),
    name: match[2],
    description: migration.description.trim(),
    statements: parseMigrationSql(migration.sql),
    exportContract: migration.exportContract ? normalizeExportContract(migration.exportContract) : null,
  });
}

function canonicalMigration(migration) {
  return JSON.stringify({
    version: migration.version,
    description: migration.description,
    statements: migration.statements,
    exportContract: migration.exportContract,
  });
}

async function sha256(value) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function prepareMigrationPlan(migrations) {
  const normalized = migrations.map(normalizeMigration);
  const ordinals = new Set();
  const names = new Set();
  const versions = new Set();
  for (const migration of normalized) {
    if (versions.has(migration.version)) throw new MigrationError("duplicate_version");
    if (ordinals.has(migration.ordinal)) throw new MigrationError("duplicate_ordinal");
    if (names.has(migration.name)) throw new MigrationError("duplicate_name");
    versions.add(migration.version);
    ordinals.add(migration.ordinal);
    names.add(migration.name);
  }

  const plan = normalized.sort((left, right) => left.ordinal - right.ordinal);
  for (let index = 0; index < plan.length; index += 1) {
    if (plan[index].ordinal !== index) throw new MigrationError("migration_gap");
  }
  if (plan.length > 0 && !plan.at(-1).exportContract) {
    throw new MigrationError("missing_export_contract");
  }

  return Promise.all(plan.map(async (migration) => Object.freeze({
    ...migration,
    checksum: await sha256(canonicalMigration(migration)),
  })));
}

async function ensureMetadata(db) {
  try {
    await db.prepare(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version TEXT PRIMARY KEY,
        checksum TEXT NOT NULL,
        applied_at TEXT NOT NULL,
        description TEXT NOT NULL
      )
    `).run();
  } catch {
    throw new MigrationError("migration_history_unavailable");
  }
}

async function readHistory(db) {
  try {
    const result = await db.prepare(
      "SELECT version, checksum, applied_at, description FROM schema_migrations ORDER BY version",
    ).all();
    return rowsOf(result);
  } catch {
    throw new MigrationError("migration_history_unavailable");
  }
}

function validateHistory(plan, history, requireComplete) {
  const byVersion = new Map(plan.map((migration) => [migration.version, migration]));
  for (let index = 0; index < history.length; index += 1) {
    const applied = history[index];
    const expected = byVersion.get(applied.version);
    if (!expected) throw new MigrationError("unknown_applied_migration");
    if (plan[index]?.version !== applied.version) throw new MigrationError("applied_migration_gap");
    if (expected.checksum !== applied.checksum) {
      throw new MigrationError("applied_migration_tampered");
    }
    if (expected.description !== applied.description) {
      throw new MigrationError("applied_migration_description_tampered");
    }
  }
  if (requireComplete && history.length !== plan.length) {
    throw new MigrationError("migration_history_incomplete");
  }
}

export async function readMigrationAuthority(db, migrations) {
  const plan = await prepareMigrationPlan(migrations);
  const history = await readHistory(db);
  validateHistory(plan, history, true);
  const latest = plan.at(-1);
  if (!latest) throw new MigrationError("empty_migration_plan");
  return Object.freeze({
    brand: AUTHORITY,
    currentVersion: latest.version,
    tables: latest.exportContract.tables,
    history: Object.freeze(history.map((row) => Object.freeze({ ...row }))),
  });
}

export function assertMigrationAuthority(authority) {
  if (authority?.brand !== AUTHORITY) throw new MigrationError("migration_authority_required");
  return authority;
}

export async function migrate(db, migrations, { now = () => new Date().toISOString() } = {}) {
  const plan = await prepareMigrationPlan(migrations);
  await ensureMetadata(db);
  const history = await readHistory(db);
  validateHistory(plan, history, false);

  const appliedNow = [];
  for (const migration of plan.slice(history.length)) {
    const statements = migration.statements.map((sql) => db.prepare(sql));
    statements.push(
      db.prepare(
        "INSERT INTO schema_migrations(version, checksum, applied_at, description) VALUES (?, ?, ?, ?)",
      ).bind(migration.version, migration.checksum, now(), migration.description),
    );
    try {
      await db.batch(statements);
      appliedNow.push(migration.version);
    } catch {
      throw new MigrationError("migration_failed");
    }
  }

  const verified = await readHistory(db);
  validateHistory(plan, verified, true);
  return Object.freeze({
    currentVersion: plan.at(-1)?.version || null,
    appliedNow: Object.freeze(appliedNow),
    history: Object.freeze(verified.map((row) => Object.freeze({ ...row }))),
  });
}
