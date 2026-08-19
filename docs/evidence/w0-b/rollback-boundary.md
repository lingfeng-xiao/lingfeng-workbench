# W0-B application and data rollback boundary

Current known-good production remains Sites version **3**. This PR creates only a repository candidate and does not create a saved Sites version.

A later G4 packet must name the exact candidate version and exact fallback version before changing production. Application rollback may only switch the application version; it must not overwrite D1 or R2. Compatibility must be checked against the live schema before either direction.

Migration and restore rehearsals use newly created, isolated temporary databases. A failed rehearsal is rolled back by discarding only that temporary database. The unique online D1 database is never a rehearsal target.

Any online restore, destructive migration, production cleanup, R2 deletion, access-policy expansion or data-boundary change requires an exact G3 approval. If R2 cannot be read/listed, the affected acceptance criterion remains blocked rather than treated as empty.

## Hardening boundary

The machine route is fail-closed and has no positive production path in this candidate. Rolling
back this source commit must not run or reverse any D1 migration automatically. The nonce migration
is source-only until separately authorized, and the temporary-only isolation attestation table is
never part of the production migration set.
