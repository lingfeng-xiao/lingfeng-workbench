import sqlite3
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from lingfeng_workbench.product_contracts import (
    ActorRole, AgentRuntime, ArtifactCandidate, ArtifactProvenance,
    ArtifactReference, ArtifactSourceKind, Capability, DataClass, Decision,
    DecisionOutcome, GateKind, Interaction, InteractionState,
    IsolatedArtifactPolicyRegistry, Mission, Node, ObjectType, PrincipalClaims,
    ProductArea, Proposal, ProposalState, Release, ReleaseState, Run,
    WorkItem, WorkState, contract_from_dict, record_hash,
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
        self.now = datetime(2026, 8, 19, tzinfo=timezone.utc)
        provenance = (
            ArtifactProvenance(
                "artifact-safe", "a" * 64, ObjectType.WORK_ITEM, "wi-1",
                ArtifactSourceKind.SYNTHETIC_FIXTURE, "generated-fixture",
                "policy-safe-1",
            ),
            ArtifactProvenance(
                "artifact-bad", "b" * 64, ObjectType.WORK_ITEM, "wi-1",
                ArtifactSourceKind.COMPANY_CODE, "company-source",
                "policy-deny-1",
            ),
            ArtifactProvenance(
                "artifact-path", "c" * 64, ObjectType.WORK_ITEM, "wi-1",
                ArtifactSourceKind.WORKBENCH_DESIGN, "C:\\company\\source.py",
                "policy-path-1",
            ),
            ArtifactProvenance(
                "artifact-export", "d" * 64, ObjectType.WORK_ITEM, "wi-1",
                ArtifactSourceKind.USER_EXPORT, "confirmed-export",
                "policy-export-1",
            ),
        )
        self.registry = IsolatedArtifactPolicyRegistry(provenance, isolated=True)
        self.store = IsolatedSqliteContractStore(
            self.connection,
            isolated=True,
            artifact_policy_registry=self.registry,
            server_clock=lambda: self.now,
            decision_ttl_seconds=300,
        )
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
        self.store.append(Capability("cap-1", 1, NOW, "area-1", "Contracts"), self.user)

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
        created_at="1999-01-01T00:00:00.000Z",
    ):
        return Decision(
            decision_id, 1, created_at, gate, target.object_type, target.id,
            target.version if target_version is None else target_version,
            record_hash(target) if target_hash is None else target_hash,
            scope, "user-1", ActorRole.USER, outcome, f"replay-{decision_id}",
        )

    def prepare_proposal(self):
        proposal = Proposal("proposal-1", 1, NOW, "cap-1", (), "Exact proposal")
        self.store.append(proposal, self.user)
        return self.store.transition_state(
            ObjectType.PROPOSAL, proposal.id, ProposalState.PROPOSED, self.user
        )

    def prepare_release(self):
        proposal = Proposal("proposal-release", 1, NOW, "cap-1", (), "Release proposal")
        self.store.append(proposal, self.user)
        release = Release("release-1", 1, NOW, proposal.id, "a" * 40, "site-v1")
        self.store.append(release, self.user)
        return self.store.transition_state(
            ObjectType.RELEASE, release.id, ReleaseState.READY, self.user
        )


class GateSecurityTests(SecureStoreCase):
    def test_identity_and_persisted_reconstruction_cannot_be_self_asserted(self):
        with self.assertRaises(PermissionError):
            AuthenticatedPrincipal(PrincipalClaims("fake-user", ActorRole.USER))

        proposal = Proposal("proposal-forged", 1, NOW, "cap-1", (), "Forged")
        payload = proposal.to_dict()
        payload["state"] = ProposalState.ACCEPTED.value
        with self.assertRaises(PermissionError):
            contract_from_dict(payload)
        self.assertFalse(hasattr(
            __import__(
                "lingfeng_workbench.product_contracts.models",
                fromlist=["contract_from_persisted_dict"],
            ),
            "contract_from_persisted_dict",
        ))
        forged = self.store._reconstruct(payload)
        with self.assertRaises(PermissionError):
            self.store.append(forged, self.user)

    def test_every_stateful_object_requires_its_unique_initial_state(self):
        with self.assertRaises(PermissionError):
            self.store.append(
                WorkItem(
                    "wi-terminal", 1, NOW, "Title", "Objective",
                    "node-1", "node-1:terminal", WorkState.COMPLETED,
                ),
                self.user,
            )
        self.store.append(
            AgentRuntime("runtime-1", 1, NOW, "node-1", "ws"), self.user
        )
        self.store.append(Mission("mission-1", 1, NOW, "wi-1", "Objective"), self.user)
        with self.assertRaises(PermissionError):
            self.store.append(
                Run(
                    "run-terminal", 1, NOW, "mission-1", "runtime-1",
                    WorkState.COMPLETED,
                ),
                self.user,
            )
        self.store.append(
            Run("run-1", 1, NOW, "mission-1", "runtime-1"), self.user
        )
        with self.assertRaises(PermissionError):
            self.store.append(
                Interaction(
                    "interaction-terminal", 1, NOW, "run-1", "approval",
                    "Prompt", InteractionState.RESOLVED,
                ),
                self.user,
            )

    def test_gate_binds_version_hash_scope_principal_and_consumes_once(self):
        proposal = self.prepare_proposal()
        scope = "Implement exact proposal"
        stale = self.make_decision(
            proposal, decision_id="decision-stale", gate=GateKind.PROPOSAL,
            scope=scope, target_version=1,
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(stale, self.user)
        forged = self.make_decision(
            proposal, decision_id="decision-forged", gate=GateKind.PROPOSAL,
            scope=scope,
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(forged, self.workbench)

        accepted = self.make_decision(
            proposal, decision_id="decision-accepted", gate=GateKind.PROPOSAL,
            scope=scope,
        )
        persisted = self.store.record_decision(accepted, self.user)
        self.assertEqual(NOW, persisted.created_at)
        self.assertNotEqual(accepted.created_at, persisted.created_at)
        with self.assertRaises(PermissionError):
            self.store.transition_state(
                ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
                self.user, decision_id=persisted.id, scope="Expanded scope",
            )
        updated = self.store.transition_state(
            ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
            self.user, decision_id=persisted.id, scope=scope,
        )
        self.assertIs(ProposalState.ACCEPTED, updated.state)
        with self.assertRaises(PermissionError):
            self.store._consume_decision(
                decision_id=persisted.id, action_id="second-action",
                gate=GateKind.PROPOSAL, target=proposal, scope=scope,
                outcome=DecisionOutcome.ACCEPT, principal=self.user,
            )

    def test_release_and_rollback_each_require_a_fresh_exact_g4(self):
        release = self.prepare_release()
        deploy = self.make_decision(
            release, decision_id="decision-deploy", gate=GateKind.RELEASE,
            scope="Deploy site-v1",
        )
        deploy = self.store.record_decision(deploy, self.user)
        released = self.store.transition_state(
            ObjectType.RELEASE, release.id, ReleaseState.RELEASED, self.user,
            decision_id=deploy.id, scope="Deploy site-v1",
        )
        with self.assertRaises(PermissionError):
            self.store.transition_state(
                ObjectType.RELEASE, release.id, ReleaseState.ROLLED_BACK, self.user
            )
        rollback = self.make_decision(
            released, decision_id="decision-rollback", gate=GateKind.RELEASE,
            scope="Rollback site-v1",
        )
        rollback = self.store.record_decision(rollback, self.user)
        rolled_back = self.store.transition_state(
            ObjectType.RELEASE, release.id, ReleaseState.ROLLED_BACK, self.user,
            decision_id=rollback.id, scope="Rollback site-v1",
        )
        self.assertIs(ReleaseState.ROLLED_BACK, rolled_back.state)
        self.assertEqual(
            2,
            self.connection.execute("SELECT COUNT(*) FROM gate_consumptions").fetchone()[0],
        )

    def test_decision_expiry_future_and_over_age_are_rejected(self):
        proposal = self.prepare_proposal()
        expired = self.store.record_decision(
            self.make_decision(
                proposal, decision_id="decision-expired",
                gate=GateKind.PROPOSAL, scope="Expire",
            ),
            self.user,
        )
        self.now += timedelta(seconds=301)
        with self.assertRaises(PermissionError):
            self.store.transition_state(
                ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
                self.user, decision_id=expired.id, scope="Expire",
            )

        self.now -= timedelta(seconds=301)
        future = self.store.record_decision(
            self.make_decision(
                proposal, decision_id="decision-future",
                gate=GateKind.PROPOSAL, scope="Future",
            ),
            self.user,
        )
        self.connection.execute(
            "UPDATE gate_decisions SET issued_at = ?, expires_at = ? WHERE decision_id = ?",
            (
                "2026-08-19T00:01:00.000Z",
                "2026-08-19T00:06:01.000Z",
                future.id,
            ),
        )
        with self.assertRaises(PermissionError):
            self.store.transition_state(
                ObjectType.PROPOSAL, proposal.id, ProposalState.ACCEPTED,
                self.user, decision_id=future.id, scope="Future",
            )

    def test_sensitive_change_has_an_exact_g3_target_and_one_time_consume(self):
        release = self.prepare_release()
        g3 = self.store.record_decision(
            self.make_decision(
                release, decision_id="decision-g3",
                gate=GateKind.SENSITIVE_CHANGE,
                scope="Apply exact D1 migration hash abc",
            ),
            self.user,
        )
        self.store.consume_sensitive_change(
            release.id, g3.id, "Apply exact D1 migration hash abc", self.user
        )
        with self.assertRaises(PermissionError):
            self.store.consume_sensitive_change(
                release.id, g3.id, "Apply exact D1 migration hash abc", self.user
            )
        proposal = self.prepare_proposal()
        wrong = self.make_decision(
            proposal, decision_id="decision-wrong-g3",
            gate=GateKind.SENSITIVE_CHANGE, scope="Wrong target",
        )
        with self.assertRaises(PermissionError):
            self.store.record_decision(wrong, self.user)


class ArtifactSecurityTests(SecureStoreCase):
    def append_artifact(self, artifact_id, sha):
        item = ArtifactReference(
            artifact_id, 1, NOW, ObjectType.WORK_ITEM, "wi-1",
            DataClass.LOCAL_ONLY, "server generated candidate", sha, 12,
        )
        self.store.append(item, self.user)
        return item

    @staticmethod
    def candidate(artifact_id, sha, storage="object-1"):
        return ArtifactCandidate(
            artifact_id, sha, ObjectType.WORK_ITEM, "wi-1",
            storage, "non-authoritative caller label",
        )

    def test_candidate_cannot_self_report_source_and_unregistered_is_denied(self):
        with self.assertRaises(TypeError):
            ArtifactCandidate(
                "artifact-x", "e" * 64, ObjectType.WORK_ITEM, "wi-1",
                "object-x", "label",
                source_kind=ArtifactSourceKind.SYNTHETIC_FIXTURE,
            )
        unknown = self.append_artifact("artifact-unknown", "e" * 64)
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                self.candidate(unknown.id, unknown.sha256), self.user
            )

    def test_trusted_policy_is_persisted_and_disallowed_provenance_fails(self):
        safe = self.append_artifact("artifact-safe", "a" * 64)
        promoted = self.store.promote_artifact(
            self.candidate(safe.id, safe.sha256), self.user
        )
        self.assertIs(DataClass.CLOUD_SAFE, promoted.data_class)
        self.assertEqual(ArtifactSourceKind.SYNTHETIC_FIXTURE, promoted.source_kind)
        self.assertEqual("generated-fixture", promoted.source_locator)
        self.assertEqual("policy-safe-1", promoted.policy_evidence)

        bad = self.append_artifact("artifact-bad", "b" * 64)
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                self.candidate(bad.id, bad.sha256), self.user
            )
        path = self.append_artifact("artifact-path", "c" * 64)
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                self.candidate(path.id, path.sha256), self.user
            )

    def test_plain_append_and_origin_cannot_claim_cloud_or_local_paths(self):
        with self.assertRaises(PermissionError):
            self.store.append(
                ArtifactReference(
                    "artifact-self-report", 1, NOW, ObjectType.WORK_ITEM, "wi-1",
                    DataClass.LOCAL_ONLY, "candidate", "f" * 64, 1,
                    source_kind=ArtifactSourceKind.SYNTHETIC_FIXTURE,
                    source_locator="generated", policy_evidence="caller-policy",
                ),
                self.user,
            )
        for origin in (
            "C:\\office\\report.txt",
            "file:///C:/office/report.txt",
            "/home/user/report.txt",
        ):
            with self.subTest(origin=origin):
                with self.assertRaises(PermissionError):
                    ArtifactReference(
                        "artifact-path-origin", 1, NOW, ObjectType.WORK_ITEM,
                        "wi-1", DataClass.LOCAL_ONLY, origin, "9" * 64, 1,
                    )

    def test_export_decision_binds_registry_provenance_and_storage(self):
        artifact = self.append_artifact("artifact-export", "d" * 64)
        candidate = self.candidate(
            artifact.id, artifact.sha256, "export-object-1"
        )
        provenance = self.registry.resolve(candidate)
        scope = artifact_export_scope(candidate, provenance, artifact.version)
        exact = self.store.record_decision(
            self.make_decision(
                artifact, decision_id="decision-export",
                gate=GateKind.ARTIFACT_EXPORT, scope=scope,
            ),
            self.user,
        )
        changed_storage = self.candidate(
            artifact.id, artifact.sha256, "export-object-2"
        )
        with self.assertRaises(PermissionError):
            self.store.promote_artifact(
                changed_storage, self.user, decision_id=exact.id
            )
        promoted = self.store.promote_artifact(
            candidate, self.user, decision_id=exact.id
        )
        self.assertEqual(exact.id, promoted.user_confirmation_decision_id)
        self.assertEqual("policy-export-1", promoted.policy_evidence)


if __name__ == "__main__":
    unittest.main()
