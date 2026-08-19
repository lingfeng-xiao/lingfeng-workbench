import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from lingfeng_workbench.product_contracts import (
    ActorRole, AgentRuntime, ArtifactReference, Capability, CloudSafeKind,
    ControlEvent, DataClass, Decision, DecisionOutcome, GateKind, Interaction,
    Mission, Node, ObjectType, Observation, PrincipalClaims, ProductArea,
    Proposal, ProposalState, Release, ReleaseState, Run, WorkItem,
    contract_from_dict,
)
from lingfeng_workbench.product_contracts.auth import IsolatedIdentityProvider
from lingfeng_workbench.product_contracts.storage import IsolatedSqliteContractStore

NOW = "2026-08-19T00:00:00.000Z"


def records():
    return [
        WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", "node-1:ws-1"),
        Mission("mission-1", 1, NOW, "wi-1", "Mission objective"),
        Run("run-1", 1, NOW, "mission-1", "runtime-1"),
        Interaction("interaction-1", 1, NOW, "run-1", "approval", "Exact action"),
        Node("node-1", 1, NOW, "Office computer"),
        AgentRuntime("runtime-1", 1, NOW, "node-1", "ws", ("events",)),
        ArtifactReference(
            "artifact-1", 1, NOW, ObjectType.WORK_ITEM, "wi-1",
            DataClass.LOCAL_ONLY, "synthetic source", "a" * 64, 10,
        ),
        ControlEvent(
            "event-1", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
            "created", "Created",
        ),
        ProductArea("area-1", 1, NOW, "Platform"),
        Capability("cap-1", 1, NOW, "area-1", "Contracts"),
        Observation("obs-1", 1, NOW, "cap-1", "Missing contract"),
        Proposal("proposal-1", 1, NOW, "cap-1", ("obs-1",), "Add contract"),
        Release("release-1", 1, NOW, "proposal-1", "b" * 40, "site-v1"),
        Decision(
            "decision-1", 1, NOW, GateKind.PROPOSAL, ObjectType.PROPOSAL,
            "proposal-1", 1, "c" * 64, "Implement exact proposal",
            "user-1", ActorRole.USER, DecisionOutcome.ACCEPT, "replay-1",
        ),
    ]


class ContractTests(unittest.TestCase):
    def test_all_fourteen_objects_round_trip(self):
        items = records()
        self.assertEqual(set(ObjectType), {item.object_type for item in items})
        for item in items:
            wire = json.loads(json.dumps(item.to_dict()))
            self.assertEqual(item, contract_from_dict(wire))

    def test_gated_terminal_states_cannot_be_directly_constructed(self):
        with self.assertRaises(PermissionError):
            Proposal(
                "proposal-1", 2, NOW, "cap-1", (), "Proposal",
                ProposalState.ACCEPTED,
            )
        with self.assertRaises(PermissionError):
            Release(
                "release-1", 2, NOW, "proposal-1", "a" * 40, "site-v1",
                ReleaseState.RELEASED,
            )

    def test_workspace_reference_rejects_absolute_and_cross_node_values(self):
        for value in ("C:\\company\\source", "/company/source", "node-2:ws-1"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", value)


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.store = IsolatedSqliteContractStore(self.connection, isolated=True)
        provider = IsolatedIdentityProvider(
            (PrincipalClaims("user-1", ActorRole.USER),), isolated=True
        )
        self.user = provider.authenticate("user-1")
        self.store.append(Node("node-1", 1, NOW, "Office"), self.user)

    def tearDown(self):
        self.connection.close()

    def test_store_is_memory_only_and_enables_foreign_keys(self):
        self.assertEqual(1, self.connection.execute("PRAGMA foreign_keys").fetchone()[0])
        with tempfile.TemporaryDirectory() as directory:
            file_connection = sqlite3.connect(str(Path(directory) / "contract.db"))
            try:
                with self.assertRaises(RuntimeError):
                    IsolatedSqliteContractStore(file_connection, isolated=True)
            finally:
                file_connection.close()

    def test_relationships_versions_and_event_sequences_are_enforced(self):
        with self.assertRaises(KeyError):
            self.store.append(
                Mission("mission-missing", 1, NOW, "wi-missing", "Objective"),
                self.user,
            )
        work = WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", "node-1:ws")
        self.store.append(work, self.user)
        with self.assertRaises(ValueError):
            self.store.append(
                WorkItem("wi-1", 3, NOW, "Title", "Objective", "node-1", "node-1:ws"),
                self.user,
            )
        self.store.append(
            WorkItem("wi-1", 2, NOW, "Title", "Updated", "node-1", "node-1:ws"),
            self.user,
        )
        self.store.append(
            ControlEvent(
                "event-1", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                "updated", "Updated",
            ),
            self.user,
        )
        with self.assertRaises(ValueError):
            self.store.append(
                ControlEvent(
                    "event-2", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 3,
                    "gap", "Gap",
                ),
                self.user,
            )

    def test_plain_append_cannot_self_classify_cloud_safe_or_secret(self):
        work = WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", "node-1:ws")
        self.store.append(work, self.user)
        for data_class in (DataClass.CLOUD_SAFE, DataClass.SECRET):
            with self.subTest(data_class=data_class):
                item = ArtifactReference(
                    f"artifact-{data_class.value}", 1, NOW, ObjectType.WORK_ITEM,
                    "wi-1", data_class, "safe label", "a" * 64, 1,
                    "object-1" if data_class is DataClass.CLOUD_SAFE else None,
                    CloudSafeKind.SYNTHETIC_FIXTURE
                    if data_class is DataClass.CLOUD_SAFE else None,
                )
                with self.assertRaises(PermissionError):
                    self.store.append(item, self.user)


if __name__ == "__main__":
    unittest.main()
