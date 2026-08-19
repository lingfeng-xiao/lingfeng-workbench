import json
import sqlite3
import unittest

from lingfeng_workbench.product_contracts import (
    ActorRole,
    AgentRuntime,
    ArtifactReference,
    Capability,
    CloudSafeKind,
    ControlEvent,
    DataClass,
    Decision,
    DecisionOutcome,
    GateKind,
    Interaction,
    Mission,
    Node,
    ObjectType,
    Observation,
    ProductArea,
    Proposal,
    Release,
    Run,
    SqliteContractStore,
    WorkItem,
    contract_from_dict,
)


NOW = "2026-08-19T00:00:00.000Z"


def all_contract_objects():
    return [
        WorkItem(
            "wi-1", 1, NOW, "Prepare release", "Verify the exact release",
            "node-1", "node-1:workspace-1",
        ),
        Mission("mission-1", 1, NOW, "wi-1", "Run synthetic checks"),
        Run("run-1", 1, NOW, "mission-1", "runtime-1"),
        Interaction(
            "interaction-1", 1, NOW, "run-1", "approval", "Approve exact action"
        ),
        Node("node-1", 1, NOW, "Office computer"),
        AgentRuntime(
            "runtime-1", 1, NOW, "node-1", "ws", ("structured-events",)
        ),
        ArtifactReference(
            "artifact-1",
            1,
            NOW,
            ObjectType.WORK_ITEM,
            "wi-1",
            DataClass.CLOUD_SAFE,
            "synthetic test fixture",
            "a" * 64,
            12,
            "object-1",
            CloudSafeKind.SYNTHETIC_FIXTURE,
        ),
        ControlEvent(
            "event-1",
            1,
            NOW,
            ObjectType.WORK_ITEM,
            "wi-1",
            1,
            "created",
            "Work item created",
        ),
        ProductArea("area-1", 1, NOW, "Workbench platform"),
        Capability("capability-1", 1, NOW, "area-1", "Product contracts"),
        Observation(
            "observation-1", 1, NOW, "capability-1", "A contract is missing"
        ),
        Proposal(
            "proposal-1",
            1,
            NOW,
            "capability-1",
            ("observation-1",),
            "Add the product contract",
        ),
        Release(
            "release-1",
            1,
            NOW,
            "proposal-1",
            "b" * 40,
            "site-v1",
        ),
        Decision(
            "decision-1",
            1,
            NOW,
            GateKind.PROPOSAL,
            ObjectType.PROPOSAL,
            "proposal-1",
            1,
            "Implement the accepted proposal",
            "user-1",
            ActorRole.USER,
            DecisionOutcome.ACCEPT,
            "replay-1",
        ),
    ]


class ContractRoundTripTests(unittest.TestCase):
    def test_all_fourteen_objects_round_trip(self):
        records = all_contract_objects()
        self.assertEqual(14, len(records))
        self.assertEqual(set(ObjectType), {record.object_type for record in records})
        for record in records:
            with self.subTest(object_type=record.object_type.value):
                wire = json.loads(json.dumps(record.to_dict()))
                self.assertEqual(record, contract_from_dict(wire))

    def test_declared_space_cannot_be_changed(self):
        payload = all_contract_objects()[0].to_dict()
        payload["space"] = "workbench"
        with self.assertRaises(ValueError):
            contract_from_dict(payload)

    def test_workspace_reference_is_node_scoped_and_opaque(self):
        with self.assertRaises(ValueError):
            WorkItem(
                "wi-2", 1, NOW, "Unsafe path", "Reject absolute path",
                "node-1", "C:\\company\\source",
            )
        with self.assertRaises(ValueError):
            WorkItem(
                "wi-2", 1, NOW, "Wrong node", "Reject cross-node lookup",
                "node-1", "node-2:workspace-1",
            )


class TemporarySqliteTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.store = SqliteContractStore(self.connection)

    def tearDown(self):
        self.connection.close()

    def test_append_and_latest_version_round_trip(self):
        original = all_contract_objects()[0]
        self.store.append(original)
        updated = WorkItem(
            original.id,
            2,
            NOW,
            original.title,
            "Updated control summary",
            original.target_node_id,
            original.local_workspace_ref,
        )
        self.store.append(updated)
        self.assertEqual(original, self.store.get(ObjectType.WORK_ITEM, original.id, 1))
        self.assertEqual(updated, self.store.get(ObjectType.WORK_ITEM, original.id))

    def test_same_version_cannot_be_overwritten(self):
        original = all_contract_objects()[0]
        self.store.append(original)
        with self.assertRaises(ValueError):
            self.store.append(original)


if __name__ == "__main__":
    unittest.main()
