import sqlite3
import unittest
from dataclasses import replace

from lingfeng_workbench.product_contracts import (
    ActorRole, ArtifactCandidate, ArtifactReference, ArtifactSourceKind,
    Capability, DataClass, Decision, DecisionOutcome, GateKind, Node,
    ObjectType, PrincipalClaims, ProductArea, Proposal, ProposalState,
    Release, ReleaseState, WorkItem, record_hash,
)
from lingfeng_workbench.product_contracts.auth import (
    AuthenticatedPrincipal, IsolatedIdentityProvider,
)
from lingfeng_workbench.product_contracts.storage import (
    IsolatedSqliteContractStore, artifact_export_scope,
)

NOW = "2026-08-19T00:00:00.000Z"


class SecureStoreCase(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.store = IsolatedSqliteContractStore(self.connection, isolated=True)
        provider = IsolatedIdentityProvider(
            (
                PrincipalClaims("user-1", ActorRole.USER),
                PrincipalClaims("workbench-1", ActorRole.WORKBENCH),
            ),
            isolated=True,
        )
        self.user = provider.authenticate("user-1")
        self.workbench = provider.authenticate("workbench-1")
        self.store.append(Node("node-1", 1, NOW, "Office"), self.user)
        self.store.append(
            WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", "node-1:ws"),
            self.user,
        )
        self.store.append(ProductArea("area-1", 1, NOW, "Platform"), self.user)
        self.store.append(
            Capability("cap-1", 1, NOW, "area-1", "Contracts"), self.user
        )

    def tearDown(self):
        self.connection.close()

    def make_decision(
        self,
        target,
        *,
        decision_id,
        gate,
        scope,
        outcome=DecisionOutcome.ACCEPT,
        target_hash=None,
        target_version=None,
    ):
        return Decision(
            decision_id, 1, NOW, gate, target.object_type, target.id,
            target.version if target_version is None else target_version,
            record_hash(target) if target_hash is None else target_hash,
            scope, "user-1", ActorRole.USER, outcome, f"replay-{decision_id}",
        )


class GateSecurityTests(SecureStoreCase):
    def test_identity_cannot_be_self_asserted(self):
        with self.assertRaises(PermissionError):
            AuthenticatedPrincipal(PrincipalClaims("fake-user", ActorRole.USER))

    def test_gate_binds_persisted_version_hash_scope_principal_and_consumes_once(self):
        proposal = Proposal("proposal-1", 1, NOW, "cap-1", (), "Exact proposal")
        self.store.append(proposal, self.user)
        proposal = self.store.transition_state(
            ObjectType.PROPOSAL, "proposal-1", ProposalState.PROPOSED, self.user
        )
        scope = "Implement exact proposal"
        stale = self.make_decision(
            proposal,
            decision_id="decision-stale",
            gate=GateKind.PROPOSAL,
            scope=scope,
            target_version=1,
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(stale, self.user)
        wrong_hash = self.make_decision(
            proposal,
            decision_id="decision-wrong-hash",
            gate=GateKind.PROPOSAL,
            scope=scope,
            target_hash="0" * 64,
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(wrong_hash, self.user)
        forged = self.make_decision(
            proposal,
            decision_id="decision-forged",
            gate=GateKind.PROPOSAL,
            scope=scope,
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(forged, self.workbench)

        accepted = self.make_decision(
            proposal,
            decision_id="decision-accepted",
            gate=GateKind.PROPOSAL,
            scope=scope,
        )
        self.store.record_decision(accepted, self.user)
        with self.assertRaises(PermissionError):
            self.store.record_decision(
                replace(accepted, id="decision-replay"), self.user
            )
        with self.assertRaises(PermissionError):
            self.store.transition_state(
                ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
                self.user, decision_id=accepted.id, scope="Expanded scope",
            )
        updated = self.store.transition_state(
            ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
            self.user, decision_id=accepted.id, scope=scope,
        )
        self.assertIs(ProposalState.ACCEPTED, updated.state)
        self.assertEqual(
            1,
            self.connection.execute("SELECT COUNT(*) FROM gate_consumptions").fetchone()[0],
        )
        with self.assertRaises(PermissionError):
            self.store._consume_decision(
                decision_id=accepted.id,
                action_id="second-action",
                gate=GateKind.PROPOSAL,
                target=proposal,
                scope=scope,
                outcome=DecisionOutcome.ACCEPT,
                principal=self.user,
            )
        with self.assertRaises(ValueError):
            self.store.transition_state(
                ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
                self.user, decision_id=accepted.id, scope=scope,
            )

    def test_proposal_gate_cannot_release_and_release_needs_exact_g4(self):
        proposal = Proposal("proposal-1", 1, NOW, "cap-1", (), "Exact proposal")
        self.store.append(proposal, self.user)
        release = Release(
            "release-1", 1, NOW, proposal.id, "a" * 40, "site-v1"
        )
        self.store.append(release, self.user)
        release = self.store.transition_state(
            ObjectType.RELEASE, release.id, ReleaseState.READY, self.user
        )
        wrong_gate = self.make_decision(
            release,
            decision_id="decision-g1",
            gate=GateKind.PROPOSAL,
            scope="Deploy site-v1",
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(wrong_gate, self.user)
        exact = self.make_decision(
            release,
            decision_id="decision-g4",
            gate=GateKind.RELEASE,
            scope="Deploy site-v1",
        )
        self.store.record_decision(exact, self.user)
        released = self.store.transition_state(
            ObjectType.RELEASE, release.id, ReleaseState.RELEASED, self.user,
            decision_id=exact.id, scope="Deploy site-v1",
        )
        self.assertIs(ReleaseState.RELEASED, released.state)


class ArtifactSecurityTests(SecureStoreCase):
    def append_artifact(self, artifact_id="artifact-1", sha="a" * 64):
        item = ArtifactReference(
            artifact_id, 1, NOW, ObjectType.WORK_ITEM, "wi-1",
            DataClass.LOCAL_ONLY, "local candidate", sha, 12,
        )
        self.store.append(item, self.user)
        return item

    def candidate(
        self,
        source_kind,
        *,
        artifact_id="artifact-1",
        sha="a" * 64,
        locator="generated-fixture",
        storage="object-1",
        label="Workbench design report",
    ):
        return ArtifactCandidate(
            artifact_id, sha, ObjectType.WORK_ITEM, "wi-1", source_kind,
            locator, storage, label,
        )

    def test_disallowed_sources_cannot_hide_behind_a_safe_summary(self):
        self.append_artifact()
        disallowed = (
            ArtifactSourceKind.COMPANY_CODE,
            ArtifactSourceKind.SOURCE_DIFF,
            ArtifactSourceKind.RAW_LOG,
            ArtifactSourceKind.SQL,
            ArtifactSourceKind.DATABASE_EXPORT,
            ArtifactSourceKind.CUSTOMER_DATA,
            ArtifactSourceKind.PRODUCTION_DATA,
            ArtifactSourceKind.BUSINESS_TEST_REPORT,
            ArtifactSourceKind.BUILD_REPORT,
            ArtifactSourceKind.RUNTIME_CONVERSATION,
            ArtifactSourceKind.ABSOLUTE_PATH,
            ArtifactSourceKind.SECRET,
            ArtifactSourceKind.UNKNOWN,
        )
        for kind in disallowed:
            with self.subTest(kind=kind.value):
                with self.assertRaises(PermissionError):
                    self.store.promote_artifact(self.candidate(kind), self.user)
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                self.candidate(
                    ArtifactSourceKind.WORKBENCH_DESIGN,
                    locator="C:\\company\\source.py",
                ),
                self.user,
            )

    def test_policy_not_record_self_report_promotes_synthetic_fixture(self):
        self.append_artifact()
        promoted = self.store.promote_artifact(
            self.candidate(ArtifactSourceKind.SYNTHETIC_FIXTURE), self.user
        )
        self.assertIs(DataClass.CLOUD_SAFE, promoted.data_class)
        self.assertEqual("synthetic_fixture", promoted.cloud_safe_kind.value)

    def test_export_decision_binds_hash_owner_kind_and_storage_object(self):
        artifact = self.append_artifact("artifact-export", "b" * 64)
        candidate = self.candidate(
            ArtifactSourceKind.USER_EXPORT,
            artifact_id=artifact.id,
            sha=artifact.sha256,
            storage="export-object-1",
        )
        scope = artifact_export_scope(candidate, artifact.version)
        exact = self.make_decision(
            artifact,
            decision_id="decision-export",
            gate=GateKind.ARTIFACT_EXPORT,
            scope=scope,
        )
        self.store.record_decision(exact, self.user)
        changed_storage = self.candidate(
            ArtifactSourceKind.USER_EXPORT,
            artifact_id=artifact.id,
            sha=artifact.sha256,
            storage="export-object-2",
        )
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                changed_storage, self.user, decision_id=exact.id
            )
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                self.candidate(
                    ArtifactSourceKind.SYNTHETIC_FIXTURE,
                    artifact_id=artifact.id,
                    sha=artifact.sha256,
                    storage=candidate.storage_ref,
                ),
                self.user,
                decision_id=exact.id,
            )
        promoted = self.store.promote_artifact(
            candidate, self.user, decision_id=exact.id
        )
        self.assertEqual(exact.id, promoted.user_confirmation_decision_id)


if __name__ == "__main__":
    unittest.main()
