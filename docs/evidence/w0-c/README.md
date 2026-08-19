# W0-C contract evidence

This directory describes the commit-bound v0.2 contract candidate. It does not claim
that cloud validation has passed.

The candidate contains exactly two top-level spaces and fourteen versioned objects.
Security-sensitive state is owned by an isolated contract store: terminal Proposal and
Release states cannot be directly constructed, persisted transitions are continuous,
and a Gate binds the authenticated user, target ID, persisted version, canonical hash,
scope, outcome, and a one-time atomic consumption.

Artifact promotion is server policy, not a record-supplied classification. The closed
source vocabulary rejects company code or diffs, raw logs, SQL and database exports,
customer or production data, business build/test reports, Runtime conversations,
absolute paths, secrets, and unknown sources even when a summary claims the content is
safe. A confirmed export additionally binds the exact content hash, owner, kind, and
storage object to a consumed user decision.

Cross-space event and artifact relationships require a persisted explicit reference,
real endpoints, and a matching audit event. Runtime access uses authenticated
principal, Node, and Runtime identity together. The SQLite adapter is deliberately
in-memory and isolated-test-only; it is not the cloud fact-source implementation.

Safe cloud validation commands:

```bash
python -m compileall -q lingfeng_workbench/product_contracts tests/product_contracts
python -m unittest discover -s tests/product_contracts -v
```

No production migration/write, upload, real approval replay, full page, merge, or
deployment is performed by this candidate. Passing workflow URLs and exact results must
only be added after cloud execution against the exact commit.
