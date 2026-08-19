# W0-B Gate 0 candidate evidence

Status: implementation candidate only. This branch is not merged, saved as a Sites version, or deployed.

## Read-only platform baseline

Read back on 2026-08-19:

- exactly one long-lived Site named **Lingfeng Workbench**;
- project `appgprj_6a841dad1a8881919399cc5bced2c838` is active with custom/private access;
- one owner and no viewers, editors, external visitors or groups (personal identity values intentionally omitted);
- current production is version 3;
- no access-policy change, D1/R2 write, cleanup, restore, Site creation or deployment was performed.

The repository binds the candidate to that existing project through `.openai/hosting.json`; it never calls create-site.

## Candidate controls

- Browser business authorization accepts only a server-provided human principal in the server-side subject allowlist. Page visibility is not authorization.
- The machine route requires both a trusted Sites-edge assertion and an application HMAC for Hermes. Missing, expired, invalid and replayed claims are denied; audit events contain only rule, status and request ID.
- The migration runner verifies a consecutive, checksum-bound history and uses atomic D1 batches.
- Logical restore refuses online or non-empty targets and validates format, version, checksum, cloud-safe reference policy and relationships using synthetic data.
- The static shell contains no product navigation, business objects or company context.

## Cloud validation commands

```sh
npm test
npm run build
git diff --exit-code -- RECOVERY_MANIFEST.sha256 SERVER_RECOVERY.md
```

The authoritative evidence is the GitHub Actions run attached to the exact PR head. This file does not claim a passing run before that run exists.

## Explicit incomplete acceptance criteria

The following are still incomplete and must not be inferred from source tests:

- real authorized/unauthorized browser sessions against a saved candidate;
- the positive Hermes path, because the current custom/private edge blocks machine requests before Worker execution;
- readable R2 binding and paginated object evidence;
- a real temporary D1 migration/restore rehearsal through the Sites control plane;
- a GitHub-traceable saved Sites version and application rollback rehearsal;
- production D1/R2 cleanup and second empty check.

Production D1 write/restore/cleanup, R2 deletion and access expansion stop at G3. Saving/deploying or rolling back an exact Sites version stops at G4.
