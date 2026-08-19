"""Frozen v1 navigation, route, and authenticated permission contract."""

from __future__ import annotations

from dataclasses import dataclass

from .auth import AuthenticatedPrincipal, require_authenticated
from .enums import ActorRole, ObjectType, Space
from .validation import identifier

API_VERSION = "v1"
API_PREFIX = "/api/v1"
TOP_LEVEL_SPACES = (Space.MY_WORK, Space.WORKBENCH)


@dataclass(frozen=True, slots=True)
class PageContract:
    key: str
    label: str
    space: Space
    route: str
    breadcrumbs: tuple[str, ...]
    empty_state: str
    object_link_templates: tuple[str, ...]
    business_api_prefix: str
    object_types: tuple[ObjectType, ...]
    read_roles: frozenset[ActorRole]
    write_roles: frozenset[ActorRole]


_USER_PAGE_ROLES = frozenset({ActorRole.USER})

PAGES = (
    PageContract(
        "home", "首页", Space.MY_WORK, "/my-work", ("我的工作",),
        "暂无工作摘要", ("/my-work/items/{work_item_id}",), "/api/v1/my-work",
        (ObjectType.WORK_ITEM,), _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "work_items", "需求与任务", Space.MY_WORK, "/my-work/items",
        ("我的工作", "需求与任务"), "暂无需求或任务",
        ("/my-work/items/{work_item_id}",), "/api/v1/my-work/work-items",
        (ObjectType.WORK_ITEM,), _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "work_item_detail", "任务详情", Space.MY_WORK,
        "/my-work/items/{work_item_id}", ("我的工作", "需求与任务", "任务详情"),
        "任务尚无运行或产物", ("/my-work/approvals", "/my-work/artifacts"),
        "/api/v1/my-work/work-items/{work_item_id}",
        (
            ObjectType.WORK_ITEM, ObjectType.MISSION, ObjectType.RUN,
            ObjectType.INTERACTION, ObjectType.ARTIFACT_REFERENCE,
        ),
        _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "approvals", "审批中心", Space.MY_WORK, "/my-work/approvals",
        ("我的工作", "审批中心"), "暂无待审批项",
        ("/my-work/items/{work_item_id}",), "/api/v1/my-work/interactions",
        (ObjectType.INTERACTION,), _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "nodes_runtimes", "Nodes & Runtimes", Space.MY_WORK,
        "/my-work/nodes-runtimes", ("我的工作", "Nodes & Runtimes"),
        "暂无已登记节点", ("/my-work/items/{work_item_id}",),
        "/api/v1/my-work/nodes",
        (ObjectType.NODE, ObjectType.AGENT_RUNTIME),
        _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "artifacts", "Artifacts", Space.MY_WORK, "/my-work/artifacts",
        ("我的工作", "Artifacts"), "暂无产物引用",
        ("/my-work/items/{work_item_id}",), "/api/v1/my-work/artifact-references",
        (ObjectType.ARTIFACT_REFERENCE,), _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
    PageContract(
        "workbench", "Workbench", Space.WORKBENCH, "/workbench",
        ("Workbench",), "暂无产品观察或提案",
        ("/workbench?proposal={proposal_id}", "/workbench?release={release_id}"),
        "/api/v1/workbench",
        (
            ObjectType.PRODUCT_AREA, ObjectType.CAPABILITY,
            ObjectType.OBSERVATION, ObjectType.PROPOSAL,
            ObjectType.RELEASE, ObjectType.DECISION,
        ),
        _USER_PAGE_ROLES, _USER_PAGE_ROLES,
    ),
)

OBJECT_API_ROUTES = {
    ObjectType.WORK_ITEM: "/api/v1/my-work/work-items",
    ObjectType.MISSION: "/api/v1/my-work/missions",
    ObjectType.RUN: "/api/v1/my-work/runs",
    ObjectType.INTERACTION: "/api/v1/my-work/interactions",
    ObjectType.NODE: "/api/v1/my-work/nodes",
    ObjectType.AGENT_RUNTIME: "/api/v1/my-work/agent-runtimes",
    ObjectType.ARTIFACT_REFERENCE: "/api/v1/my-work/artifact-references",
    ObjectType.CONTROL_EVENT: "/api/v1/my-work/control-events",
    ObjectType.PRODUCT_AREA: "/api/v1/workbench/product-areas",
    ObjectType.CAPABILITY: "/api/v1/workbench/capabilities",
    ObjectType.OBSERVATION: "/api/v1/workbench/observations",
    ObjectType.PROPOSAL: "/api/v1/workbench/proposals",
    ObjectType.RELEASE: "/api/v1/workbench/releases",
    ObjectType.DECISION: "/api/v1/workbench/decisions",
}
OBJECT_SPACES = {
    object_type: (
        Space.MY_WORK
        if object_type in {
            ObjectType.WORK_ITEM, ObjectType.MISSION, ObjectType.RUN,
            ObjectType.INTERACTION, ObjectType.NODE, ObjectType.AGENT_RUNTIME,
            ObjectType.ARTIFACT_REFERENCE, ObjectType.CONTROL_EVENT,
        }
        else Space.WORKBENCH
    )
    for object_type in ObjectType
}
READ_PERMISSIONS = {
    ActorRole.USER: frozenset(ObjectType),
    ActorRole.HERMES: frozenset(
        kind for kind, space in OBJECT_SPACES.items() if space is Space.MY_WORK
    ),
    ActorRole.AGENT_RUNTIME: frozenset(
        {
            ObjectType.WORK_ITEM, ObjectType.MISSION, ObjectType.RUN,
            ObjectType.INTERACTION, ObjectType.ARTIFACT_REFERENCE,
            ObjectType.CONTROL_EVENT,
        }
    ),
    ActorRole.WORKBENCH: frozenset(
        kind for kind, space in OBJECT_SPACES.items() if space is Space.WORKBENCH
    ),
}
WRITE_PERMISSIONS = {
    ActorRole.USER: frozenset(ObjectType),
    ActorRole.HERMES: READ_PERMISSIONS[ActorRole.HERMES],
    ActorRole.AGENT_RUNTIME: frozenset(
        {
            ObjectType.RUN, ObjectType.INTERACTION,
            ObjectType.ARTIFACT_REFERENCE, ObjectType.CONTROL_EVENT,
        }
    ),
    ActorRole.WORKBENCH: frozenset({ObjectType.OBSERVATION, ObjectType.PROPOSAL}),
}


def authorize_operation(
    principal: AuthenticatedPrincipal,
    object_type: ObjectType,
    *,
    write: bool = False,
    target_node_id: str | None = None,
    target_runtime_id: str | None = None,
) -> None:
    require_authenticated(principal)
    object_type = ObjectType(object_type)
    matrix = WRITE_PERMISSIONS if write else READ_PERMISSIONS
    if object_type not in matrix[principal.role]:
        raise PermissionError("principal cannot access the requested object")
    if principal.role is ActorRole.AGENT_RUNTIME:
        if target_node_id is None or target_runtime_id is None:
            raise PermissionError("runtime access requires exact node and runtime ownership")
        if (
            identifier(target_node_id, "target_node_id") != principal.node_id
            or identifier(target_runtime_id, "target_runtime_id") != principal.runtime_id
        ):
            raise PermissionError("cross-node or cross-runtime access is denied")


def authorize_page(
    principal: AuthenticatedPrincipal, page_key: str, *, write: bool = False
) -> PageContract:
    require_authenticated(principal)
    page_key = identifier(page_key, "page_key")
    try:
        page = next(item for item in PAGES if item.key == page_key)
    except StopIteration as exc:
        raise KeyError("page does not exist") from exc
    roles = page.write_roles if write else page.read_roles
    if principal.role not in roles:
        raise PermissionError("principal cannot access the requested page action")
    return page


def validate_api_contract() -> None:
    if TOP_LEVEL_SPACES != (Space.MY_WORK, Space.WORKBENCH):
        raise AssertionError("exactly two top-level spaces are required")
    if set(OBJECT_API_ROUTES) != set(ObjectType):
        raise AssertionError("every object requires one route")
    expected_pages = (
        "home", "work_items", "work_item_detail", "approvals",
        "nodes_runtimes", "artifacts", "workbench",
    )
    if tuple(page.key for page in PAGES) != expected_pages:
        raise AssertionError("the frozen contract requires exactly seven ordered pages")
    page_routes = tuple(page.route for page in PAGES)
    object_routes = tuple(OBJECT_API_ROUTES.values())
    if len(set(page_routes)) != len(page_routes) or len(set(object_routes)) != len(object_routes):
        raise AssertionError("page and object API routes must be unique")
    routes = object_routes + tuple(page.business_api_prefix for page in PAGES)
    if any(not (route == API_PREFIX or route.startswith(f"{API_PREFIX}/")) for route in routes):
        raise AssertionError("business routes must use the exact /api/v1 prefix")

    page_route_set = set(page_routes)
    for page in PAGES:
        if not page.breadcrumbs or not page.empty_state or not page.object_link_templates:
            raise AssertionError("page navigation metadata is incomplete")
        expected_page_prefix = "/my-work" if page.space is Space.MY_WORK else "/workbench"
        expected_api_prefix = "/api/v1/my-work" if page.space is Space.MY_WORK else "/api/v1/workbench"
        if not (
            page.route == expected_page_prefix
            or page.route.startswith(f"{expected_page_prefix}/")
        ):
            raise AssertionError("page route does not match its top-level space")
        if not (
            page.business_api_prefix == expected_api_prefix
            or page.business_api_prefix.startswith(f"{expected_api_prefix}/")
        ):
            raise AssertionError("page API does not match its top-level space")
        for link in page.object_link_templates:
            link_path = link.split("?", 1)[0]
            if link_path not in page_route_set:
                raise AssertionError("page object link does not target a frozen page")
        if not page.object_types or not page.read_roles or not page.write_roles:
            raise AssertionError("page role/action contract is incomplete")
        for role in page.read_roles:
            if any(kind not in READ_PERMISSIONS[role] for kind in page.object_types):
                raise AssertionError("page read role conflicts with object permissions")
        for role in page.write_roles:
            if any(kind not in WRITE_PERMISSIONS[role] for kind in page.object_types):
                raise AssertionError("page write role conflicts with object permissions")
