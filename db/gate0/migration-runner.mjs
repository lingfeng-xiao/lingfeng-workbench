const encoder = new TextEncoder();
const VERSION_PATTERN = /^(\d{4})_[a-z0-9_]+$/u;
const STATEMENT_MARKER = /^\s*--\s*gate0:statement\s*$/imu;

export class MigrationError extends Error {
  constructor(code, detail = "") {
    super(detail ? `${code}: ${detail}` : code);
    this.name = "MigrationError";
    this.code = code;
  }
}

function rowsOf(result) {
  if (Array.isArray(result)) return result;
  if (Array.isArray(result?.results)) return result.results;
  return [];
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
  const parts = sql.split(STATEMENT_MARKER).map((part) => part.trim()).filter(Boolean);
  return parts.map(normalizeStatement);
}

function normalizeMigration(migration) {
  const match = VERSION_PATTERN.exec(migration.version || "");
  if (!match) throw new MigrationError("invalid_version", String(migration.version));
  if (typeof migration.description !== "string" || !migration.description.trim()) {
    throw new MigrationError("missing_description", migration.version);
  }
  return Object.freeze({
    version: migration.version,
    ordinal: Number(match[1]),
    description: migration.description.trim(),
    statements: parseMigrationSql(migration.sql),
  });
}

function canonicalMigration(migration) {
  return JSON.stringify({
    version: migration.version,
    description: migration.description,
    statements: migration.statements,
  });
}

async function sha256(value) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function prepareMigrationPlan(migrations) {
  const plan = migrations.map(normalizeMigration).sort((left, right) => left.ordinal - right.ordinal);
  for (let index = 0; index < plan.length; index += 1) {
    if (plan[index].ordinal !== index) {
      throw new MigrationError("migration_gap", plan[index].version);
    }
    if (index > 0 && plan[index - 1].version === plan[index].version) {
      throw new MigrationError("duplicate_version", plan[index].version);
    }
  }
  return Promise.all(plan.map(async (migration) => Object.freeze({
    ...migration,
    checksum: await sha256(canonicalMigration(migration)),
  })));
}

async function ensureMetadata(db) {
  await db.prepare(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version TEXT PRIMARY KEY,
      checksum TEXT NOT NULL,
      applied_at TEXT NOT NULL,
      description TEXT NOT NULL
    )
  `).run();
}

async function readHistory(db) {
  const result = await db.prepare(
    "SELECT version, checksum, applied_at, description FROM schema_migrations ORDER BY version",
  ).all();
  return rowsOf(result);
}

export async function migrate(db, migrations, { now = () => new Date().toISOString() } = {}) {
  const plan = await prepareMigrationPlan(migrations);
  await ensureMetadata(db);
  const history = await readHistory(db);
  const byVersion = new Map(plan.map((migration) => [migration.version, migration]));

  for (let index = 0; index < history.length; index += 1) {
    const applied = history[index];
    const expected = byVersion.get(applied.version);
    if (!expected) throw new MigrationError("unknown_applied_migration", applied.version);
    if (plan[index]?.version !== applied.version) {
      throw new MigrationError("applied_migration_gap", applied.version);
    }
    if (expected.checksum !== applied.checksum) {
      throw new MigrationError("applied_migration_tampered", applied.version);
    }
  }

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
      throw new MigrationError("migration_failed", migration.version);
    }
  }

  const verified = await readHistory(db);
  if (verified.length !== plan.length) throw new MigrationError("history_length_mismatch");
  for (let index = 0; index < plan.length; index += 1) {
    if (verified[index].version !== plan[index].version || verified[index].checksum !== plan[index].checksum) {
      throw new MigrationError("history_verification_failed", plan[index].version);
    }
  }

  return Object.freeze({
    currentVersion: plan.at(-1)?.version || null,
    appliedNow: Object.freeze(appliedNow),
    history: Object.freeze(verified.map((row) => Object.freeze({ ...row }))),
  });
}
