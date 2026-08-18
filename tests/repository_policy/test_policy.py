from __future__ import annotations

import unittest

from tools.repository_policy.policy import (
    MAX_PUBLIC_BLOB_BYTES,
    PolicyExecutionError,
    Finding,
    format_finding,
    parse_recovery_manifest,
    sanitize_location,
    scan_bytes,
    scan_path_policy,
)


class RepositoryPolicyTests(unittest.TestCase):
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
