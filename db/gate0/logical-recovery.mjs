const encoder = new TextEncoder();
const IDENTIFIER = /^[a-z][a-z0-9_]{0,62}$/u;

export class RecoveryError extends Error {
  constructor(code, detail = "") {
    super(detail ? `${code}: ${detail}` : code);
    this.name = "RecoveryError";
    this.code = code;
  }
}

function rowsOf(result) {
  if (Array.isArray(result)) return result;
  if (Array.isArray(result?.results)) return result.results;
  return [];
}

function assertIdentifier(value) {
  if (!IDENTIFIER.test(value)) throw new RecoveryError("unsafe_identifier", String(value));
  return value;
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
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(canonical(value))));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function normalizeSpecs(tableSpecs) {
  const seen = new Set();
  return tableSpecs.map((spec) => {
    const name = assertIdentifier(spec.name);
    if (seen.has(name)) throw new RecoveryError("duplicate_table", name);
    seen.add(name);
    const primaryKey = spec.primaryKey.map(assertIdentifier);
    if (primaryKey.length === 0) throw new RecoveryError("missing_primary_key", name);
    return Object.freeze({ name, primaryKey: Object.freeze(primaryKey) });
  });
}

export async function createLogicalExport(db, {
  tableSpecs,
  schemaVersion,
  generatedAt = new Date().toISOString(),
}) {
  if (typeof schemaVersion !== "string" || !schemaVersion) {
    throw new RecoveryError("missing_schema_version");
  }
  const specs = normalizeSpecs(tableSpecs);
  const tables = [];
  for (const spec of specs) {
    const order = spec.primaryKey.map((column) => `"${column}"`).join(", ");
    const result = await db.prepare(`SELECT * FROM "${spec.name}" ORDER BY ${order}`).all();
    tables.push({ name: spec.name, primaryKey: spec.primaryKey, rows: rowsOf(result) });
  }
  const payload = {
    format: "lingfeng-gate0-logical-export-v1",
    schemaVersion,
    generatedAt,
    tables,
  };
  return Object.freeze({ ...payload, checksum: await checksum(payload) });
}

function assertShape(exported, specs) {
  if (exported?.format !== "lingfeng-gate0-logical-export-v1") {
    throw new RecoveryError("unsupported_export_format");
  }
  if (!Array.isArray(exported.tables) || exported.tables.length !== specs.length) {
    throw new RecoveryError("table_set_mismatch");
  }
  for (let index = 0; index < specs.length; index += 1) {
    const table = exported.tables[index];
    const spec = specs[index];
    if (table?.name !== spec.name || canonical(table.primaryKey) !== canonical(spec.primaryKey)) {
      throw new RecoveryError("table_contract_mismatch", spec.name);
    }
    if (!Array.isArray(table.rows)) throw new RecoveryError("invalid_rows", spec.name);
  }
}

export async function restoreLogicalExport(target, exported, {
  tableSpecs,
  allowedSchemaVersions,
  validateExport = async () => ({ ok: true }),
  verify = async () => ({ ok: true }),
}) {
  if (target?.isIsolated !== true) throw new RecoveryError("online_target_forbidden");
  const specs = normalizeSpecs(tableSpecs);
  assertShape(exported, specs);
  if (!(allowedSchemaVersions instanceof Set) || !allowedSchemaVersions.has(exported.schemaVersion)) {
    throw new RecoveryError("schema_version_rejected", String(exported.schemaVersion));
  }

  const { checksum: suppliedChecksum, ...payload } = exported;
  if (typeof suppliedChecksum !== "string" || suppliedChecksum !== await checksum(payload)) {
    throw new RecoveryError("checksum_rejected");
  }

  const preflight = await validateExport(exported);
  if (preflight?.ok !== true) throw new RecoveryError("export_preflight_failed");

  for (const spec of specs) {
    const result = await target.db.prepare(`SELECT COUNT(*) AS count FROM "${spec.name}"`).first();
    if (Number(result?.count) !== 0) throw new RecoveryError("target_not_empty", spec.name);
  }

  const statements = [];
  for (const table of exported.tables) {
    for (const row of table.rows) {
      const columns = Object.keys(row).sort().map(assertIdentifier);
      if (columns.length === 0) throw new RecoveryError("empty_row", table.name);
      const columnSql = columns.map((column) => `"${column}"`).join(", ");
      const placeholders = columns.map(() => "?").join(", ");
      statements.push(
        target.db.prepare(
          `INSERT INTO "${table.name}" (${columnSql}) VALUES (${placeholders})`,
        ).bind(...columns.map((column) => row[column])),
      );
    }
  }
  try {
    if (statements.length > 0) await target.db.batch(statements);
  } catch {
    throw new RecoveryError("restore_batch_failed");
  }

  const counts = {};
  for (const spec of specs) {
    const result = await target.db.prepare(`SELECT COUNT(*) AS count FROM "${spec.name}"`).first();
    counts[spec.name] = Number(result?.count);
  }
  const verification = await verify(target.db, exported);
  if (verification?.ok !== true) throw new RecoveryError("relationship_verification_failed");

  return Object.freeze({
    schemaVersion: exported.schemaVersion,
    checksum: suppliedChecksum,
    counts: Object.freeze(counts),
    verification: Object.freeze({ ...verification }),
  });
}
