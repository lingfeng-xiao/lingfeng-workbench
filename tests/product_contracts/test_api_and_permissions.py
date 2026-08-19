import sqlite3
import unittest
from datetime import datetime, timezone

from lingfeng_workbench.product_contracts import (
    ActorRole, AgentRuntime, ArtifactReference, Capability, ControlEvent,
    CrossSpaceReference, DataClass, Interaction, Mission, Node, OBJECT_API_ROUTES,
    PAGES, TOP_LEVEL_SPACES, ObjectType, Observation, PrincipalClaims,
    ProductArea, Proposal, Run, Space, WorkItem, authorize_operation,
    authorize_page, record_hash, validate_api_contract,
)
from lingfeng_workbench.product_contracts.auth import IsolatedIdentityProvider
from lingfeng_workbench.product_contracts.storage import IsolatedSqliteContractStore

NOW = "2026-08-19T00:00:00.000Z"


class ApiTests(unittest.TestCase):
    def setUp(self):
        provider = IsolatedIdentityProvider(
            (
                PrincipalClaims("user-1", ActorRole.USER),
                PrincipalClaims("workbench-1", ActorRole.WORKBENCH),
                PrincipalClaims(
                    "runtime-1", ActorRole.AGENT_RUNTIME, "node-1", "runtime-1"
                ),
            ),
            isolated=True,
        )
        self.user = provider.authenticate("user-1")
        self.workbench = provider.authenticate("workbench-1")
        self.runtime = provider.authenticate("runtime-1")

    def test_frozen_exact_pages_routes_links_roles_and_api_prefix(self):
        validate_api_contract()
        self.assertEqual((Space.MY_WORK, Space.WORKBENCH), TOP_LEVEL_SPACES)
        self.assertEqual(set(ObjectType), set(OBJECT_API_ROUTES))
        self.assertEqual(
            (
                "home", "work_items", "work_item_detail", "approvals",
                "nodes_runtimes", "artifacts", "workbench",
            ),
            tuple(page.key for page in PAGES),
        )
        self.assertEqual(len(PAGES), len({page.route for page in PAGES}))
        self.assertEqual(
            len(OBJECT_API_ROUTES), len(set(OBJECT_API_ROUTES.values()))
        )
        for page in PAGES:
            self.assertTrue(page.breadcrumbs)
            self.assertTrue(page.empty_state)
            self.assertTrue(page.object_link_templates)
            self.assertTrue(page.object_types)
            self.assertEqual(frozenset({ActorRole.USER}), page.read_roles)
            self.assertEqual(frozenset({ActorRole.USER}), page.write_roles)
            authorize_page(self.user, page.key)
            authorize_page(self.user, page.key, write=True)
            with self.assertRaises(PermissionError):
                authorize_page(self.runtime, page.key)

    def test_workbench_may_draft_but_cannot_decide_release_or_cross_space(self):
        authorize_operation(self.workbench, ObjectType.PROPOSAL, write=True)
        for protected in (
            ObjectType.DECISION, ObjectType.RELEASE, ObjectType.WORK_ITEM
        ):
            with self.subTest(protected=protected.value):
                with self.assertRaises(PermissionError):
                    authorize_operation(self.workbench, protected, write=True)

    def test_runtime_requires_exact_node_and_runtime_on_every_access(self):
        authorize_operation(
            self.runtime, ObjectType.RUN, write=True,
            target_node_id="node-1", target_runtime_id="runtime-1",
        )
        for node_id, runtime_id in (
            (None, None),
            ("node-2", "runtime-1"),
            ("node-1", "runtime-2"),
        ):
            with self.subTest(node_id=node_id, runtime_id=runtime_id):
                with self.assertRaises(PermissionError):
                    authorize_operation(
                        self.runtime, ObjectType.RUN, write=True,
                        target_node_id=node_id, target_runtime_id=runtime_id,
                    )


class PersistenceBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.store = IsolatedSqliteContractStore(
            self.connection,
            isolated=True,
            server_clock=lambda: datetime(2026, 8, 19, tzinfo=timezone.utc),
        )
        provider = IsolatedIdentityProvider(
            (
                PrincipalClaims("user-1", ActorRole.USER),
                PrincipalClaims(
                    "runtime-1", ActorRole.AGENT_RUNTIME, "node-1", "runtime-1"
                ),
                PrincipalClaims(
                    "runtime-2", ActorRole.AGENT_RUNTIME, "node-2", "runtime-2"
                ),
            ),
            isolated=True,
        )
        self.user = provider.authenticate("user-1")
        self.runtime1 = provider.authenticate("runtime-1")
        self.runtime2 = provider.authenticate("runtime-2")
        self.store.append(Node("node-1", 1, NOW, "Office one"), self.user)
        self.store.append(Node("node-2", 1, NOW, "Office two"), self.user)
        self.store.append(
            WorkItem("wi-1", 1, NOW, "Title", "Objective", "node-1", "node-1:ws"),
            self.user,
        )
        self.store.append(
            WorkItem("wi-2", 1, NOW, "Title two", "Objective", "node-2", "node-2:ws"),
            self.user,
        )
        self.store.append(Mission("mission-1", 1, NOW, "wi-1", "Objective"), self.user)
        self.store.append(Mission("mission-2", 1, NOW, "wi-2", "Objective"), self.user)
        self.store.append(
            AgentRuntime("runtime-1", 1, NOW, "node-1", "ws"), self.user
        )
        self.store.append(
            AgentRuntime("runtime-2", 1, NOW, "node-2", "ws"), self.user
        )
        self.store.append(
            Run("run-1", 1, NOW, "mission-1", "runtime-1"), self.user
        )
        self.store.append(ProductArea("area-1", 1, NOW, "Platform"), self.user)
        self.store.append(Capability("cap-1", 1, NOW, "area-1", "Contracts"), self.user)
        self.store.append(
            Observation("obs-1", 1, NOW, "cap-1", "Observation one"), self.user
        )
        self.store.append(
            Observation("obs-2", 1, NOW, "cap-1", "Observation two"), self.user
        )
        self.store.append(
            Proposal("proposal-1", 1, NOW, "cap-1", ("obs-1",), "Proposal"),
            self.user,
        )

    def tearDown(self):
        self.connection.close()

    def reference(self, reference_id="cross-1", *, source_hash=None):
        source = self.store.read(ObjectType.WORK_ITEM, "wi-1", self.user)
        target = self.store.read(ObjectType.PROPOSAL, "proposal-1", self.user)
        event = self.store.read(ObjectType.CONTROL_EVENT, "event-audit", self.user)
        return CrossSpaceReference(
            reference_id, ObjectType.WORK_ITEM, source.id,
            ObjectType.PROPOSAL, target.id, event.id,
            source.version, source_hash or record_hash(source),
            target.version, record_hash(target),
            event.version, record_hash(event), "user-1", "1999-01-01T00:00:00.000Z",
        )

    def test_run_creation_and_every_runtime_owned_version_use_persisted_node(self):
        with self.assertRaises(PermissionError):
            self.store.append(
                Run("run-cross-node", 1, NOW, "mission-1", "runtime-2"),
                self.user,
            )
        self.store.append(
            Run("run-1", 2, NOW, "mission-1", "runtime-1"), self.runtime1
        )
        with self.assertRaises(PermissionError):
            self.store.append(
                Run("run-1", 3, NOW, "mission-1", "runtime-2"), self.runtime2
            )
        with self.assertRaises(PermissionError):
            self.store.append(
                Run("run-1", 3, NOW, "mission-1", "runtime-2"), self.runtime1
            )

        interaction = Interaction(
            "interaction-1", 1, NOW, "run-1", "approval", "Exact prompt"
        )
        self.store.append(interaction, self.runtime1)
        with self.assertRaises(PermissionError):
            self.store.append(
                Interaction(
                    interaction.id, 2, NOW, "run-1", "approval", "Updated"
                ),
                self.runtime2,
            )

    def test_runtime_read_follows_persisted_run_mission_work_item_chain(self):
        self.assertEqual(
            "mission-1",
            self.store.read(ObjectType.MISSION, "mission-1", self.runtime1).id,
        )
        self.assertEqual(
            "wi-1",
            self.store.read(ObjectType.WORK_ITEM, "wi-1", self.runtime1).id,
        )
        for principal, object_type, object_id in (
            (self.runtime1, ObjectType.MISSION, "mission-2"),
            (self.runtime1, ObjectType.WORK_ITEM, "wi-2"),
            (self.runtime2, ObjectType.MISSION, "mission-1"),
            (self.runtime2, ObjectType.WORK_ITEM, "wi-1"),
        ):
            with self.subTest(
                runtime=principal.runtime_id,
                object_type=object_type.value,
                object_id=object_id,
            ):
                with self.assertRaises(PermissionError):
                    self.store.read(object_type, object_id, principal)

    def test_all_ownership_and_relation_fields_are_immutable(self):
        attempts = (
            WorkItem(
                "wi-1", 2, NOW, "Title", "Objective",
                "node-2", "node-2:moved",
            ),
            Mission("mission-1", 2, NOW, "wi-2", "Objective"),
            ArtifactReference(
                "artifact-owner", 2, NOW, ObjectType.WORK_ITEM, "wi-2",
                DataClass.LOCAL_ONLY, "candidate", "a" * 64, 1,
            ),
            Proposal(
                "proposal-1", 2, NOW, "cap-1", ("obs-2",), "Proposal"
            ),
        )
        self.store.append(
            ArtifactReference(
                "artifact-owner", 1, NOW, ObjectType.WORK_ITEM, "wi-1",
                DataClass.LOCAL_ONLY, "candidate", "a" * 64, 1,
            ),
            self.user,
        )
        for record in attempts:
            with self.subTest(record=record.object_type.value):
                with self.assertRaises(PermissionError):
                    self.store.append(record, self.user)

        self.store.append(
            ControlEvent(
                "event-immutable", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                "created", "Created",
            ),
            self.user,
        )
        with self.assertRaises(PermissionError):
            self.store.append(
                ControlEvent(
                    "event-immutable", 2, NOW, ObjectType.WORK_ITEM, "wi-2", 1,
                    "created", "Changed",
                ),
                self.user,
            )

    def test_cross_space_reference_binds_versions_hashes_creator_time_and_event(self):
        self.store.append(
            ControlEvent(
                "event-audit", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                "link_requested", "User requested a traceable link",
            ),
            self.user,
        )
        with self.assertRaises(PermissionError):
            self.store.add_cross_space_reference(
                self.reference("cross-bad-hash", source_hash="0" * 64), self.user
            )
        persisted = self.store.add_cross_space_reference(self.reference(), self.user)
        self.assertEqual(NOW, persisted.created_at)
        self.assertEqual("user-1", persisted.creator_id)

        self.store.append(
            ControlEvent(
                "event-cross", 1, NOW, ObjectType.PROPOSAL, "proposal-1", 1,
                "linked", "Explicit relationship recorded", "cross-1",
            ),
            self.user,
        )
        self.store.append(
            ArtifactReference(
                "artifact-cross", 1, NOW, ObjectType.PROPOSAL, "proposal-1",
                DataClass.LOCAL_ONLY, "local reference only", "b" * 64, 10,
                cross_space_reference_id="cross-1",
                cross_space_work_item_id="wi-1",
            ),
            self.user,
        )
        with self.assertRaises(PermissionError):
            self.store.append(
                ArtifactReference(
                    "artifact-unrelated", 1, NOW, ObjectType.PROPOSAL, "proposal-1",
                    DataClass.LOCAL_ONLY, "local reference only", "c" * 64, 10,
                    cross_space_reference_id="cross-1",
                    cross_space_work_item_id="wi-2",
                ),
                self.user,
            )

    def test_same_space_record_cannot_carry_missing_cross_reference(self):
        with self.assertRaises(PermissionError):
            self.store.append(
                ControlEvent(
                    "event-same-space", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                    "audit", "Audit", "missing-reference",
                ),
                self.user,
            )

    def test_reference_requires_real_objects_and_matching_audit_subject(self):
        with self.assertRaises(KeyError):
            self.store.add_cross_space_reference(
                CrossSpaceReference(
                    "cross-missing", ObjectType.WORK_ITEM, "wi-1",
                    ObjectType.PROPOSAL, "proposal-missing", "event-missing",
                    1, "a" * 64, 1, "b" * 64, 1, "c" * 64,
                    "user-1", NOW,
                ),
                self.user,
            )
        self.store.append(
            ControlEvent(
                "event-wrong", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                "audit", "Audit",
            ),
            self.user,
        )
        source = self.store.read(ObjectType.PROPOSAL, "proposal-1", self.user)
        target = self.store.read(ObjectType.WORK_ITEM, "wi-1", self.user)
        event = self.store.read(ObjectType.CONTROL_EVENT, "event-wrong", self.user)
        with self.assertRaises(PermissionError):
            self.store.add_cross_space_reference(
                CrossSpaceReference(
                    "cross-wrong", ObjectType.PROPOSAL, source.id,
                    ObjectType.WORK_ITEM, target.id, event.id,
                    source.version, record_hash(source),
                    target.version, record_hash(target),
                    event.version, record_hash(event), "user-1", NOW,
                ),
                self.user,
            )


if __name__ == "__main__":
    unittest.main()
