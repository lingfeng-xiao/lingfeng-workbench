# Lingfeng Workbench repository rules

This repository is public and is the source of truth for Workbench source code.

- Development, builds, tests, commits, and pushes happen only in an approved cloud environment or cloud CI. The office computer may only pull and run approved source.
- Never commit secrets, credentials, cookies, private keys, company code or diffs, raw logs, raw SQL or database exports, customer or production data, runtime conversations, office-computer absolute paths, or unclassified data.
- Content suitable for this public repository is not automatically allowed in R2. The R2 cloud-safe allowlist is a separate and closed boundary.
- Synthetic fixtures must be unmistakably synthetic, assembled at test runtime when they resemble credentials, and must never be printed by a failing check.
- Preserve the recovery commit and the `v0.1.0-server-recovered` tag.
- Keep computer contexts independent. Do not move a work item or its local context between computers.
- Stop before G2 merge, G3 destructive/permission/data-boundary actions, and G4 production deployment or rollback.

## Code Review Rules

### Public data boundary

Flag any change that can commit or print secret, local-only, company, customer, production, or unclassified content. The safe path is a closed allowlist, synthetic fixtures, and diagnostics that report only a rule identifier and sanitized location.

### Recovery provenance

Flag changes that rewrite the recovery commit/tag or validate recovery against the mutable working tree. Recovery evidence must be verified against the immutable recovery commit.

### Gate integrity

Flag self-authorization, stale/replayed authorization, or an implementation that merges, deploys, expands permissions, or changes the data boundary without its exact user Gate.
