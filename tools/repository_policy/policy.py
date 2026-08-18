from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence

RECOVERY_COMMIT = "968b88d9f869b0ed7a42c91e67c911f2c1e5b36c"
RECOVERY_TAG = "v0.1.0-server-recovered"
RECOVERY_MANIFEST = "RECOVERY_MANIFEST.sha256"
RECOVERY_NOTE = "SERVER_RECOVERY.md"
EXPECTED_RECOVERY_FILES = 25
MAX_PUBLIC_BLOB_BYTES = 1_048_576

CONTENT_RULES: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    (
        "private-key",
        re.compile(
            rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
            re.IGNORECASE,
        ),
    ),
    (
        "github-token",
        re.compile(
            rb"\b(?:(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}|"
            rb"github_pat_[A-Za-z0-9_]{50,})\b"
        ),
    ),
    (
        "openai-key",
        re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b"),
    ),
    (
        "aws-access-key",
        re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    ),
    (
        "credentialed-database-url",
        re.compile(
            rb"\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://"
            rb"[^/\s:@]+:[^@\s/]+@",
            re.IGNORECASE,
        ),
    ),
    (
        "office-windows-path",
        re.compile(
            rb"\b[A-Z]:[\\/]+Users[\\/]+[^\\/\r\n]+",
            re.IGNORECASE,
        ),
    ),
    (
        "office-posix-path",
        re.compile(
            rb"/(?:Users|home)/[^/\s]+/"
            rb"(?:Desktop|Documents|Downloads|workspace|work|code)(?:/|\b)",
            re.IGNORECASE,
        ),
    ),
)

FORBIDDEN_NAMES = {
    ".env",
    "auth.json",
    "cookies.json",
    "credentials.json",
    "id_rsa",
    "id_ed25519",
}
FORBIDDEN_SUFFIXES = {
    ".har",
    ".key",
    ".log",
    ".p12",
    ".pem",
    ".pfx",
    ".sqlite",
    ".sqlite3",
}


class PolicyExecutionError(RuntimeError):
    """Raised when repository evidence cannot be read deterministically."""


@dataclass(frozen=True, order=True)
class Finding:
    rule_id: str
    location: str


def sanitize_location(location: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9._/@:+-]", "?", location)
    return sanitized[:240]


def format_finding(finding: Finding) -> str:
    return f"POLICY[{finding.rule_id}] {sanitize_location(finding.location)}"


def run_git(repository: Path, *arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        command_name = " ".join(arguments[:2])
        raise PolicyExecutionError(f"git {command_name} failed")
    return completed.stdout


def parse_recovery_manifest(manifest_bytes: bytes) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(manifest_bytes.splitlines(), start=1):
        digest_bytes, separator, path_bytes = raw_line.partition(b"  ")
        if not separator or not re.fullmatch(rb"[0-9a-f]{64}", digest_bytes):
            raise PolicyExecutionError(
                f"invalid recovery manifest entry at line {line_number}"
            )
        path = path_bytes.decode("utf-8", errors="strict")
        pure_path = PurePosixPath(path)
        if pure_path.is_absolute() or ".." in pure_path.parts or path in entries:
            raise PolicyExecutionError(
                f"unsafe recovery manifest path at line {line_number}"
            )
        entries[path] = digest_bytes.decode("ascii")
    return entries


def verify_recovery_provenance(repository: Path) -> list[Finding]:
    findings: list[Finding] = []
    resolved_tag = run_git(
        repository,
        "rev-parse",
        f"{RECOVERY_TAG}^{{commit}}",
    ).decode("ascii").strip()
    if resolved_tag != RECOVERY_COMMIT:
        findings.append(Finding("recovery-tag-moved", RECOVERY_TAG))

    root_commit_parts = (
        run_git(repository, "rev-list", "--parents", "-n", "1", RECOVERY_COMMIT)
        .decode("ascii")
        .split()
    )
    if root_commit_parts != [RECOVERY_COMMIT]:
        findings.append(Finding("recovery-commit-not-root", RECOVERY_COMMIT))

    manifest_bytes = run_git(
        repository,
        "show",
        f"{RECOVERY_COMMIT}:{RECOVERY_MANIFEST}",
    )
    manifest_entries = parse_recovery_manifest(manifest_bytes)
    if len(manifest_entries) != EXPECTED_RECOVERY_FILES:
        findings.append(
            Finding("recovery-manifest-count", RECOVERY_MANIFEST)
        )

    for path, expected_digest in manifest_entries.items():
        recovered_bytes = run_git(
            repository,
            "show",
            f"{RECOVERY_COMMIT}:{path}",
        )
        actual_digest = hashlib.sha256(recovered_bytes).hexdigest()
        if actual_digest != expected_digest:
            findings.append(Finding("recovery-hash-mismatch", path))

    protected_changes = run_git(
        repository,
        "diff",
        "--name-only",
        RECOVERY_COMMIT,
        "--",
        RECOVERY_MANIFEST,
        RECOVERY_NOTE,
    ).decode("utf-8", errors="replace")
    for path in protected_changes.splitlines():
        findings.append(Finding("recovery-evidence-modified", path))
    return findings


def scan_path_policy(
    path: str,
    size_bytes: int,
    report_location: str | None = None,
) -> list[Finding]:
    findings: list[Finding] = []
    location = report_location or path
    path_name = PurePosixPath(path).name.lower()
    if path_name in FORBIDDEN_NAMES or path_name.startswith(".env."):
        findings.append(Finding("forbidden-file-name", location))
    if PurePosixPath(path_name).suffix in FORBIDDEN_SUFFIXES:
        findings.append(Finding("forbidden-file-type", location))
    if size_bytes > MAX_PUBLIC_BLOB_BYTES:
        findings.append(Finding("oversized-public-blob", location))
    return findings


def scan_bytes(location: str, content: bytes) -> list[Finding]:
    return [
        Finding(rule_id, location)
        for rule_id, pattern in CONTENT_RULES
        if pattern.search(content)
    ]


def list_tracked_paths(repository: Path) -> list[str]:
    tracked_bytes = run_git(repository, "ls-files", "-z")
    return [
        path.decode("utf-8", errors="surrogateescape")
        for path in tracked_bytes.split(b"\0")
        if path
    ]


def scan_current_tree(repository: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in list_tracked_paths(repository):
        local_path = repository / path
        if not local_path.is_file():
            findings.append(Finding("tracked-file-unreadable", path))
            continue
        size_bytes = local_path.stat().st_size
        findings.extend(scan_path_policy(path, size_bytes))
        if size_bytes <= MAX_PUBLIC_BLOB_BYTES:
            findings.extend(scan_bytes(path, local_path.read_bytes()))
    return findings


def parse_tree_entry(raw_entry: bytes) -> tuple[str, str, int] | None:
    metadata_bytes, separator, path_bytes = raw_entry.partition(b"\t")
    metadata_parts = metadata_bytes.split()
    if not separator or len(metadata_parts) != 4:
        raise PolicyExecutionError("invalid reachable tree entry")

    _, object_type_bytes, object_id_bytes, size_bytes = metadata_parts
    if object_type_bytes != b"blob":
        return None
    if not re.fullmatch(rb"[0-9a-f]{40,64}", object_id_bytes):
        raise PolicyExecutionError("invalid reachable blob identifier")
    if not size_bytes.isdigit():
        raise PolicyExecutionError("invalid reachable blob size")

    object_id = object_id_bytes.decode("ascii")
    path = path_bytes.decode("utf-8", errors="surrogateescape")
    return object_id, path, int(size_bytes)


def iter_reachable_blobs(repository: Path) -> Iterable[tuple[str, str, int]]:
    commit_lines = run_git(repository, "rev-list", "--all")
    seen_path_objects: set[tuple[str, str]] = set()
    for commit_id_bytes in commit_lines.splitlines():
        commit_id = commit_id_bytes.decode("ascii")
        tree_entries = run_git(
            repository,
            "ls-tree",
            "-rlz",
            "--full-tree",
            commit_id,
        )
        for raw_entry in tree_entries.split(b"\0"):
            if not raw_entry:
                continue
            parsed_entry = parse_tree_entry(raw_entry)
            if parsed_entry is None:
                continue
            object_id, path, size_bytes = parsed_entry
            path_object = (object_id, path)
            if path_object in seen_path_objects:
                continue
            seen_path_objects.add(path_object)
            yield object_id, path, size_bytes


def scan_reachable_history(repository: Path) -> list[Finding]:
    findings: list[Finding] = []
    scanned_content_objects: set[str] = set()
    for object_id, path, size_bytes in iter_reachable_blobs(repository):
        location = f"{path}@{object_id[:12]}"
        findings.extend(scan_path_policy(path, size_bytes, location))
        if (
            object_id not in scanned_content_objects
            and size_bytes <= MAX_PUBLIC_BLOB_BYTES
        ):
            scanned_content_objects.add(object_id)
            blob_bytes = run_git(repository, "cat-file", "blob", object_id)
            findings.extend(scan_bytes(location, blob_bytes))
    return findings

def run_negative_control() -> int:
    synthetic_token = b"gh" + b"p_" + (b"A" * 36)
    findings = scan_bytes("synthetic://negative-control", synthetic_token)
    if not any(finding.rule_id == "github-token" for finding in findings):
        print("negative control was not rejected", file=sys.stderr)
        return 2
    print(
        "POLICY[negative-control] synthetic credential was rejected; "
        "intentional workflow failure",
        file=sys.stderr,
    )
    return 1


def collect_findings(repository: Path, scan_history: bool) -> list[Finding]:
    findings = verify_recovery_provenance(repository)
    findings.extend(scan_current_tree(repository))
    if scan_history:
        findings.extend(scan_reachable_history(repository))
    return sorted(set(findings))


def parse_arguments(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify recovery provenance and the public repository boundary."
    )
    parser.add_argument(
        "--repository",
        type=Path,
        default=Path.cwd(),
        help="Git working tree to verify.",
    )
    parser.add_argument(
        "--scan-history",
        action="store_true",
        help="Scan blobs reachable from every fetched ref.",
    )
    parser.add_argument(
        "--negative-control",
        action="store_true",
        help="Reject an in-memory synthetic credential and exit non-zero.",
    )
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    parsed = parse_arguments(arguments)
    if parsed.negative_control:
        return run_negative_control()

    repository = parsed.repository.resolve()
    try:
        findings = collect_findings(repository, parsed.scan_history)
    except (OSError, UnicodeError, ValueError, PolicyExecutionError) as error:
        print(
            f"repository policy could not complete: {type(error).__name__}",
            file=sys.stderr,
        )
        return 2

    for finding in findings:
        print(format_finding(finding), file=sys.stderr)
    if findings:
        print(
            f"repository policy failed with {len(findings)} finding(s)",
            file=sys.stderr,
        )
        return 1

    scope = "all reachable history" if parsed.scan_history else "current tree"
    print(f"repository policy passed for {scope}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
