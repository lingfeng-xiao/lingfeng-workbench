from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import patch

from tools.repository_policy.policy import (
    MAX_PUBLIC_BLOB_BYTES,
    RECOVERY_COMMIT,
    RECOVERY_TAG_OBJECT,
    PolicyExecutionError,
    Finding,
    format_finding,
    iter_reachable_blobs,
    parse_recovery_manifest,
    sanitize_location,
    scan_bytes,
    scan_path_policy,
    verify_recovery_tag_identity,
)


class RepositoryPolicyTests(unittest.TestCase):
    def test_rejects_recreated_or_lightweight_recovery_tag(self) -> None:
        self.assertEqual(
            [],
            verify_recovery_tag_identity(
                RECOVERY_TAG_OBJECT,
                RECOVERY_COMMIT,
            ),
        )

        findings = verify_recovery_tag_identity(
            RECOVERY_COMMIT,
            RECOVERY_COMMIT,
        )

        self.assertEqual(
            [Finding("recovery-tag-object-changed", "v0.1.0-server-recovered")],
            findings,
        )

    def test_accepts_hermes_server_recovery_path(self) -> None:
        findings = scan_bytes(
            "SERVER_RECOVERY.md",
            b"/home/lingfeng/.hermes/plugins/lingfeng-workbench",
        )

        self.assertEqual([], findings)

    def test_rejects_github_token_without_echoing_it(self) -> None:
        synthetic_token = b"gh" + b"p_" + (b"A" * 36)

        findings = scan_bytes("fixture", synthetic_token)
        rendered = "\n".join(format_finding(finding) for finding in findings)

        self.assertIn("github-token", rendered)
        self.assertNotIn(synthetic_token.decode("ascii"), rendered)

    def test_rejects_office_windows_path(self) -> None:
        office_path = (
            "D:"
            + "\\"
            + "Users"
            + "\\"
            + "employee"
            + "\\"
            + "Documents"
            + "\\"
            + "client"
        ).encode("utf-8")

        findings = scan_bytes("fixture", office_path)

        self.assertIn(
            "office-windows-path",
            {finding.rule_id for finding in findings},
        )

    def test_rejects_forbidden_filename_and_oversized_blob(self) -> None:
        findings = scan_path_policy(
            "config/.env.production",
            MAX_PUBLIC_BLOB_BYTES + 1,
        )

        self.assertEqual(
            {"forbidden-file-name", "oversized-public-blob"},
            {finding.rule_id for finding in findings},
        )

    def test_historical_suffix_uses_original_path(self) -> None:
        report_location = "archive/private.pem@0123456789ab"

        findings = scan_path_policy(
            "archive/private.pem",
            128,
            report_location,
        )

        self.assertEqual(
            [Finding("forbidden-file-type", report_location)],
            findings,
        )

    def test_history_keeps_every_path_for_a_reused_blob(self) -> None:
        blob_id = "a" * 40
        first_commit = "1" * 40
        second_commit = "2" * 40

        def fake_run_git(
            repository: Path,
            *arguments: str,
        ) -> bytes:
            self.assertEqual(Path("."), repository)
            if arguments == ("rev-list", "--all"):
                return f"{first_commit}\n{second_commit}\n".encode("ascii")
            if arguments == (
                "ls-tree",
                "-rlz",
                "--full-tree",
                first_commit,
            ):
                path = "archive/readme.txt"
            elif arguments == (
                "ls-tree",
                "-rlz",
                "--full-tree",
                second_commit,
            ):
                path = "archive/private.pem"
            else:
                self.fail(f"unexpected git arguments: {arguments}")
            return (
                f"100644 blob {blob_id} 128\t{path}".encode("utf-8")
                + b"\0"
            )

        with patch(
            "tools.repository_policy.policy.run_git",
            side_effect=fake_run_git,
        ):
            reachable_blobs = list(iter_reachable_blobs(Path(".")))

        self.assertEqual(
            [
                (blob_id, "archive/readme.txt", 128),
                (blob_id, "archive/private.pem", 128),
            ],
            reachable_blobs,
        )

    def test_manifest_rejects_parent_traversal(self) -> None:
        manifest = (b"a" * 64) + b"  ../outside\n"

        with self.assertRaises(PolicyExecutionError):
            parse_recovery_manifest(manifest)

    def test_location_output_removes_control_characters(self) -> None:
        finding = Finding("example", "bad\nlocation\x1b[31m")

        rendered = format_finding(finding)

        self.assertNotIn("\n", rendered)
        self.assertNotIn("\x1b", rendered)
        self.assertEqual(
            "bad?location??31m",
            sanitize_location(finding.location),
        )


if __name__ == "__main__":
    unittest.main()
