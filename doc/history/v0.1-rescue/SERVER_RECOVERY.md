---
status: current-evidence
authority: historical-recovery
source_ref: v0.1.0-server-recovered
owner: history
superseded_by: null
last_verified: 2026-08-19
---

# Hermes Server Recovery Baseline

Recovered on 2026-08-18 entirely on the Hermes server.

- Runtime source: `/home/lingfeng/.hermes/plugins/lingfeng-workbench`
- Runtime source files: 24 non-generated files
- Runtime source-to-recovery SHA-256 verification: 24/24 matched
- Build metadata source: `/home/lingfeng/.hermes/plugin-backups/lingfeng-workbench-20260818-short-approval/pyproject.toml`
- Build metadata source-to-recovery verification: matched
- Manifest: `RECOVERY_MANIFEST.sha256` covers the 24 runtime files plus `pyproject.toml`
- Sensitive scan: 10 rules checked; 0 matched rules; 0 matched files

README, `docs/`, and `tests/` are intentionally absent because they were not present in the allowed recovery sources. Databases, logs, caches, `__pycache__`, credentials, and `.env` files were excluded. The deployed Plugin and backup source were not modified.
