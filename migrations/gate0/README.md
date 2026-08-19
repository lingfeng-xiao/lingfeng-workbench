# Gate 0 migration and recovery contract

This directory contains no v0.2 product schema. W0-B owns the generic physical mechanism and synthetic fixtures; W0-C owns logical product contracts. Any production physical migration remains a later G3 decision.

Each migration supplies a consecutive `NNNN_name` version, a description, SQL sections separated by `-- gate0:statement`, and a checksum-covered export contract. The latest contract is the sole authority for schema version, table set, columns, primary keys, relationships, state values and cloud-safe reference columns. Callers cannot override those values during export or restore.

The runner rejects duplicate versions, ordinals or names; gaps; unknown history; checksum or description changes; transaction controls; and direct migration-history writes. Errors report only sanitized classes.

Logical export reads migration history and every contracted table through one read-only D1 batch; a history change at the snapshot boundary aborts the export. BLOB values are explicitly unsupported and rejected; no lossy JSON conversion occurs. Restore accepts only an opaque temporary-D1 capability, validates the complete export before writes, and puts inserts plus SQL post-verification guards into one atomic D1 batch. Any failed guard rolls back every inserted row.

Hermes nonce consumption requires the infrastructure table described in `app/gate0/bindings.json`. Creating that table in the online database is not performed by this PR and remains a later G3-controlled physical migration.

## Runtime nonce migration

`0000_gate0_runtime.sql` is the authoritative checksum input for the persistent HMAC replay nonce
store and is packaged under `dist/.openai/drizzle/`. It is prepared but not applied to production
by this candidate. It deliberately excludes `gate0_restore_isolation_attestations`: that table may
exist only in an independently provisioned temporary restore database, where a one-time random
token is inserted out of band and atomically consumed before restore.
