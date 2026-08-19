import unittest

from lingfeng_workbench.product_contracts import (
    ActorRole,
    OBJECT_API_ROUTES,
    PAGES,
    TOP_LEVEL_SPACES,
    ObjectType,
    Space,
    authorize_operation,
    validate_api_contract,
)


class ApiAndPermissionTests(unittest.TestCase):
    def test_only_two_top_level_spaces_and_all_objects_have_v1_routes(self):
        validate_api_contract()
        self.assertEqual((Space.MY_WORK, Space.WORKBENCH), TOP_LEVEL_SPACES)
        self.assertEqual(set(ObjectType), set(OBJECT_API_ROUTES))
        self.assertTrue(all(route.startswith("/api/v1/") for route in OBJECT_API_ROUTES.values()))

    def test_fixed_navigation_has_no_third_space_or_direct_storage_route(self):
        self.assertEqual(set(TOP_LEVEL_SPACES), {page.space for page in PAGES})
        self.assertEqual(
            {
                "home",
                "work_items",
                "work_item_detail",
                "approvals",
                "nodes_runtimes",
                "artifacts",
                "workbench",
            },
            {page.key for page in PAGES},
        )
        serialized = repr(PAGES).lower()
        self.assertNotIn("d1:", serialized)
        self.assertNotIn("r2:", serialized)

    def test_workbench_can_draft_but_cannot_decide_or_release(self):
        authorize_operation(ActorRole.WORKBENCH, ObjectType.OBSERVATION, write=True)
        authorize_operation(ActorRole.WORKBENCH, ObjectType.PROPOSAL, write=True)
        for protected in (ObjectType.DECISION, ObjectType.RELEASE, ObjectType.WORK_ITEM):
            with self.subTest(protected=protected.value):
                with self.assertRaises(PermissionError):
                    authorize_operation(ActorRole.WORKBENCH, protected, write=True)

    def test_machine_roles_do_not_leak_across_spaces(self):
        authorize_operation(ActorRole.HERMES, ObjectType.WORK_ITEM, write=True)
        with self.assertRaises(PermissionError):
            authorize_operation(ActorRole.HERMES, ObjectType.PROPOSAL)
        with self.assertRaises(PermissionError):
            authorize_operation(ActorRole.AGENT_RUNTIME, ObjectType.PRODUCT_AREA)


if __name__ == "__main__":
    unittest.main()
