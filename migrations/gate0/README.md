# Gate 0 migration contract

This directory deliberately contains no v0.2 product schema. W0-B owns only the physical migration mechanism; W0-C owns logical product contracts, and a production v0.2 migration requires a later Proposal plus G3.

A migration supplied to `db/gate0/migration-runner.mjs` has:

- a consecutive `NNNN_name` version beginning at `0000`;
- a non-empty description;
- one SQL statement per section, separated by a line containing `-- gate0:statement`.

The runner rejects transaction controls and direct writes to `schema_migrations`. It hashes the normalized contract, compares the complete applied prefix, and executes every migration and its history insert through one atomic D1 `batch`. Unknown, skipped, changed and partially failed migrations are not accepted.

Only synthetic fixtures are committed. Production exports, row contents, credentials and office paths must never enter GitHub.
