# W0-B Gate 0 candidate evidence

Status: implementation candidate only. The branch is not merged, saved as a Sites version, or deployed.

## Read-only baseline

Read back on 2026-08-19: exactly one long-lived Lingfeng Workbench Site; it is active and custom/private with one owner and no viewers, editors, external visitors or groups. Current production remains version 3. Personal identity values are omitted.

No access-policy change, D1/R2 write, cleanup, restore, Site creation or deployment was performed.

## Candidate controls

- `npm run build` emits a real artifact at `dist/server/index.js`, imports authorization, routing, migration and recovery code, copies `dist/.openai/hosting.json`, and records logical D1 `DB` / R2 `ARTIFACTS` bindings.
- Runtime identity is derived only from the named trusted Sites header contract. Caller context booleans and caller request IDs are ignored.
- Browser authorization is server-side. Hermes requires the Sites machine principal plus a strong HMAC bound to audience, method, host, path, canonical query, body hash, timestamp and nonce.
- Nonces are atomically consumed in D1. The in-memory implementation is available only through an explicitly named isolated-test factory and is not used by the runtime entry.
- Migration identity covers version, description, SQL and export contract; duplicate/gap/unknown/tampered states fail with sanitized errors.
- Export schema/table plans come from verified migration history. BLOBs are rejected. Restore validates checksum, table set, states, relationships and the closed cloud-safe prefixes before writes.
- Restore requires an opaque temporary target capability. Inserts and post-write SQL guards share one D1 batch, so a failed guard rolls all rows back.

Cloud validation for each exact PR head is attached separately; this file never pre-claims a run.

## Explicit external blockers

The source and synthetic tests do not prove real platform acceptance. Still incomplete:

- browser positive/negative sessions against a saved candidate;
- positive Hermes traffic through the current custom/private Sites edge;
- readable R2 binding and paginated object evidence;
- a real temporary D1 migration/restore rehearsal through Sites;
- GitHub-traceable saved Sites version and application rollback;
- production D1/R2 cleanup and second empty check.

Production D1 writes, migrations, restores, cleanup, R2 deletion and access expansion stop at G3. Saving/deploying or rolling back an exact Sites version stops at G4.
