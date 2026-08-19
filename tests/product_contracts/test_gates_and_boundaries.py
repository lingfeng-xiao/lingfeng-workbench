import sqlite3
import unittest
from dataclasses import replace

from lingfeng_workbench.product_contracts import (
    ActorRole,
    ArtifactReference,
    CloudSafeKind,
    CrossSpaceReference,
    DataClass,
    Decision,
    DecisionOutcome,
    GateKind,
    GateLedger,
    ObjectType,
    Proposal,
    ProposalState,
    Release,
    ReleaseState,
    WorkItem,
    classification_or_default,
    validate_artifact_upload,
    validate_reference,
    validate_transition,
)


NOW = "2026-08-19T00:00:00.000Z"


def decision(
    *,
    decision_id="decision-1",
    replay_key="replay-1",
    gate=GateKind.PROPOSAL,
    target_type=ObjectType.PROPOSAL,
    target_id="proposal-1",
    target_version=1,
    scope="Implement exact proposal",
    role=ActorRole.USER,
    outcome=DecisionOutcome.ACCEPT,
):
    return Decision(
        decision_id,
        1,
        NOW,
        gate,
        target_type,
        target_id,
        target_version,
        scope,
        "user-1",
        role,
        outcome,
        replay_key,
    )


class GateTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.ledger = GateLedger(self.connection)

    def tearDown(self):
        self.connection.close()

    def test_exact_user_decision_is_recorded_and_consumed_once(self):
        accepted = decision()
        self.ledger.record(accepted, current_target_version=1)
        kwargs = dict(
            action_id="action-1",
            gate=GateKind.PROPOSAL,
            target_type=ObjectType.PROPOSAL,
            target_id="proposal-1",
            target_version=1,
            scope="Implement exact proposal",
        )
        self.ledger.consume(accepted.id, **kwargs)
        with self.assertRaises(PermissionError):
            self.ledger.consume(accepted.id, action_id="action-2", **{
                key: value for key, value in kwargs.items() if key != "action_id"
            })

    def test_replay_stale_self_authorization_and_scope_drift_are_rejected(self):
        accepted = decision()
        self.ledger.record(accepted, current_target_version=1)
        with self.assertRaises(PermissionError):
            self.ledger.record(
                decision(decision_id="decision-2", replay_key="replay-1"),
                current_target_version=1,
            )
        with self.assertRaises(PermissionError):
            self.ledger.record(
                decision(
                    decision_id="decision-3",
                    replay_key="replay-3",
                    target_version=1,
                    scope="Another scope",
                ),
                current_target_version=2,
            )
        with self.assertRaises(PermissionError):
            self.ledger.record(
                decision(
                    decision_id="decision-4",
                    replay_key="replay-4",
                    role=ActorRole.WORKBENCH,
                ),
                current_target_version=1,
            )

    def test_proposal_and_release_transitions_need_their_own_gate(self):
        proposal = Proposal(
            "proposal-1",
            1,
            NOW,
            "capability-1",
            (),
            "Exact proposal",
            ProposalState.PROPOSED,
        )
        with self.assertRaises(PermissionError):
            validate_transition(proposal, ProposalState.ACCEPTED)
        validate_transition(proposal, ProposalState.ACCEPTED, decision())

        release = Release(
            "release-1",
            3,
            NOW,
            "proposal-1",
            "a" * 40,
            "site-v3",
            ReleaseState.READY,
        )
        with self.assertRaises(PermissionError):
            validate_transition(release, ReleaseState.RELEASED, decision())
        release_decision = decision(
            decision_id="decision-release",
            replay_key="replay-release",
            gate=GateKind.RELEASE,
            target_type=ObjectType.RELEASE,
            target_id="release-1",
            target_version=3,
            scope="Deploy site-v3",
        )
        validate_transition(release, ReleaseState.RELEASED, release_decision)


class DataBoundaryTests(unittest.TestCase):
    def artifact(self, **changes):
        base = ArtifactReference(
            "artifact-1",
            1,
            NOW,
            ObjectType.WORK_ITEM,
            "wi-1",
            DataClass.CLOUD_SAFE,
            "synthetic fixture",
            "a" * 64,
            42,
            "object-1",
            CloudSafeKind.SYNTHETIC_FIXTURE,
        )
        return replace(base, **changes)

    def test_unclassified_defaults_local_and_local_or_secret_upload_is_rejected(self):
        self.assertIs(DataClass.LOCAL_ONLY, classification_or_default(None))
        for data_class in (DataClass.LOCAL_ONLY, DataClass.CONTROL, DataClass.SECRET):
            with self.subTest(data_class=data_class):
                with self.assertRaises(PermissionError):
                    validate_artifact_upload(self.artifact(data_class=data_class))

    def test_closed_allowlist_and_confirmed_export(self):
        validate_artifact_upload(self.artifact())
        with self.assertRaises(PermissionError):
            validate_artifact_upload(
                self.artifact(
                    cloud_safe_kind=CloudSafeKind.USER_CONFIRMED_EXPORT,
                    user_confirmation_decision_id=None,
                )
            )
        validate_artifact_upload(
            self.artifact(
                cloud_safe_kind=CloudSafeKind.USER_CONFIRMED_EXPORT,
                user_confirmation_decision_id="decision-safe-export",
            )
        )

    def test_cross_space_link_must_be_explicit_minimal_and_auditable(self):
        work_item = WorkItem(
            "wi-1", 1, NOW, "Track proposal", "Keep an auditable link",
            "node-1", "node-1:workspace-1",
        )
        proposal = Proposal(
            "proposal-1", 1, NOW, "capability-1", (), "Improve Workbench"
        )
        with self.assertRaises(PermissionError):
            validate_reference(work_item, proposal)
        explicit = CrossSpaceReference(
            ObjectType.WORK_ITEM,
            "wi-1",
            ObjectType.PROPOSAL,
            "proposal-1",
            "event-1",
        )
        validate_reference(work_item, proposal, explicit)


if __name__ == "__main__":
    unittest.main()
