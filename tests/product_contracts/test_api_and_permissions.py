import sqlite3
import unittest

from lingfeng_workbench.product_contracts import (
    ActorRole, AgentRuntime, ArtifactReference, ControlEvent, CrossSpaceReference,
    DataClass, Mission, Node, OBJECT_API_ROUTES, PAGES, TOP_LEVEL_SPACES,
    ObjectType, PrincipalClaims, ProductArea, Capability, Proposal, Run, Space,
    WorkItem, authorize_operation, validate_api_contract,
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

    def test_frozen_pages_have_breadcrumb_empty_state_links_and_exact_api_prefix(self):
        validate_api_contract()
        self.assertEqual((Space.MY_WORK, Space.WORKBENCH), TOP_LEVEL_SPACES)
        self.assertEqual(set(ObjectType), set(OBJECT_API_ROUTES))
        for page in PAGES:
            self.assertTrue(page.breadcrumbs)
            self.assertTrue(page.empty_state)
            self.assertTrue(page.object_link_templates)
            self.assertTrue(page.business_api_prefix.startswith("/api/v1/"))

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
        self.store = IsolatedSqliteContractStore(self.connection, isolated=True)
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
            Mission("mission-1", 1, NOW, "wi-1", "Objective"), self.user
        )
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
        self.store.append(
            Capability("cap-1", 1, NOW, "area-1", "Contracts"), self.user
        )
        self.store.append(
            Proposal("proposal-1", 1, NOW, "cap-1", (), "Proposal"), self.user
        )

    def tearDown(self):
        self.connection.close()

    def test_runtime_store_write_resolves_ownership_and_rejects_other_computer(self):
        self.store.append(
            Run("run-1", 2, NOW, "mission-1", "runtime-1"), self.runtime1
        )
        self.store.read(ObjectType.RUN, "run-1", self.runtime1)
        with self.assertRaises(PermissionError):
            self.store.read(ObjectType.RUN, "run-1", self.runtime2)
        with self.assertRaises(PermissionError):
            self.store.append(
                Run("run-1", 3, NOW, "mission-1", "runtime-1"), self.runtime2
            )

    def test_cross_space_event_and_artifact_require_persisted_matching_reference(self):
        self.store.append(
            ControlEvent(
                "event-audit", 1, NOW, ObjectType.WORK_ITEM, "wi-1", 1,
                "link_requested", "User requested a traceable link",
            ),
            self.user,
        )
        reference = CrossSpaceReference(
            "cross-1", ObjectType.WORK_ITEM, "wi-1",
            ObjectType.PROPOSAL, "proposal-1", "event-audit",
        )
        self.store.add_cross_space_reference(reference, self.user)
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
                DataClass.LOCAL_ONLY, "local reference only", "a" * 64, 10,
                cross_space_reference_id="cross-1",
            ),
            self.user,
        )
        with self.assertRaises(PermissionError):
            self.store.append(
                ArtifactReference(
                    "artifact-fake", 1, NOW, ObjectType.PROPOSAL, "proposal-1",
                    DataClass.LOCAL_ONLY, "local reference only", "b" * 64, 10,
                    cross_space_reference_id="missing-reference",
                ),
                self.user,
            )

    def test_reference_requires_real_objects_and_matching_audit_subject(self):
        with self.assertRaises(KeyError):
            self.store.add_cross_space_reference(
                CrossSpaceReference(
                    "cross-missing", ObjectType.WORK_ITEM, "wi-1",
                    ObjectType.PROPOSAL, "proposal-missing", "event-missing",
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
        with self.assertRaises(PermissionError):
            self.store.add_cross_space_reference(
                CrossSpaceReference(
                    "cross-wrong", ObjectType.PROPOSAL, "proposal-1",
                    ObjectType.WORK_ITEM, "wi-1", "event-wrong",
                ),
                self.user,
            )


if __name__ == "__main__":
    unittest.main()
