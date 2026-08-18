# W0-A repository governance evidence

## Candidate identity

- Accepted Proposal: `PROP-W0-A-v1` in Issue #2.
- Implementation branch: `codex/w0-a-repository-governance`.
- Draft PR: #7, base `main`.
- Immutable recovery commit: `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c`.
- Frozen design input: PR #1 head `3b8d8c0e5cdb5c44847c2a660bd6345b9e04d3ec`.

## Enforced candidate behavior

The `repository-policy` workflow uses only the repository-scoped
`GITHUB_TOKEN` with `contents: read`. It:

1. fetches every branch, tag, and advertised pull-request head/merge ref;
2. verifies the recovery tag, root commit, 25 manifest entries, and recovered
   file hashes against the immutable recovery commit;
3. verifies the recovery manifest and note were not changed;
4. scans the tracked tree and all blobs reachable from fetched refs;
5. reports only a rule identifier and sanitized location;
6. runs unit tests that prove a synthetic credential and office path are
   rejected without printing the fixture;
7. supports an in-memory `[negative-control]` run that intentionally fails
   without committing credential-like content.

## Evidence status

This document describes the candidate and does not claim a passing workflow
before GitHub Actions reports it. Bind final evidence to the exact passing and
deliberately failing run URLs and commit SHAs in the PR conversation.

Branch-protection binding, safe direct-push/force/delete/bypass probes, and
cross-repository denial remain separate control-plane evidence. They must not
be inferred from unit tests.

## Detection limits

This workflow is a post-push enforcement control for known signatures and
forbidden file types. It does not prove that signatureless company code,
generic JSON/CSV exports, Runtime conversations, or other unclassified content
is safe. Submitters and reviewers must classify content before commit; unknown
content remains local-only. GitHub Secret Scanning, Push Protection, and human
review are complementary controls rather than evidence that this denylist can
prevent every disclosure.

The automation identity scope is verified by reading the installation's
accessible-repository set and asserting that it contains only this repository.
The response body is parsed on the runner and is never printed.

## Rollback boundary

Before G2, rollback is deletion of this candidate branch and removal of only
the candidate required-check binding. The recovery commit, recovery tag,
existing main protection, and audit history remain unchanged.
