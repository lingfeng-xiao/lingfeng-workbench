"""Frozen v1 navigation, route, and permission contract."""

from __future__ import annotations

from dataclasses import dataclass

from .enums import ActorRole, ObjectType, Space

API_VERSION = "v1"
TOP_LEVEL_SPACES = (Space.MY_WORK, Space.WORKBENCH)


@dataclass(frozen=True, slots=True)
class PageContract:
    key: str
    label: str
    space: Space
    route: str
    business_api_prefix: str


PAGES = (
    PageContract("home", "首页", Space.MY_WORK, "/my-work", "/api/v1/my-work"),
    PageContract(
        "work_items",
        "需求与任务",
        Space.MY_WORK,
        "/my-work/items",
        "/api/v1/my-work/work-items",
    ),
    PageContract(
        "work_item_detail",
        "任务详情",
        Space.MY_WORK,
        "/my-work/items/{work_item_id}",
        "/api/v1/my-work/work-items/{work_item_id}",
    ),
    PageContract(
        "approvals",
        "审批中心",
        Space.MY_WORK,
        "/my-work/approvals",
        "/api/v1/my-work/interactions",
    ),
    PageContract(
        "nodes_runtimes",
        "Nodes & Runtimes",
        Space.MY_WORK,
        "/my-work/nodes-runtimes",
        "/api/v1/my-work/nodes",
    ),
    PageContract(
        "artifacts",
        "Artifacts",
        Space.MY_WORK,
        "/my-work/artifacts",
        "/api/v1/my-work/artifact-references",
    ),
    PageContract(
        "workbench",
        "Workbench",
        Space.WORKBENCH,
        "/workbench",
        "/api/v1/workbench",
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
        if object_type
        in {
            ObjectType.WORK_ITEM,
            ObjectType.MISSION,
            ObjectType.RUN,
            ObjectType.INTERACTION,
            ObjectType.NODE,
            ObjectType.AGENT_RUNTIME,
            ObjectType.ARTIFACT_REFERENCE,
            ObjectType.CONTROL_EVENT,
        }
        else Space.WORKBENCH
    )
    for object_type in ObjectType
}

READ_PERMISSIONS = {
    ActorRole.USER: frozenset(ObjectType),
    ActorRole.HERMES: frozenset(
        object_type
        for object_type, space in OBJECT_SPACES.items()
        if space is Space.MY_WORK
    ),
    ActorRole.AGENT_RUNTIME: frozenset(
        {
            ObjectType.WORK_ITEM,
            ObjectType.MISSION,
            ObjectType.RUN,
            ObjectType.INTERACTION,
            ObjectType.ARTIFACT_REFERENCE,
            ObjectType.CONTROL_EVENT,
        }
    ),
    ActorRole.WORKBENCH: frozenset(
        object_type
        for object_type, space in OBJECT_SPACES.items()
        if space is Space.WORKBENCH
    ),
}
WRITE_PERMISSIONS = {
    ActorRole.USER: frozenset(ObjectType),
    ActorRole.HERMES: READ_PERMISSIONS[ActorRole.HERMES],
    ActorRole.AGENT_RUNTIME: frozenset(
        {
            ObjectType.RUN,
            ObjectType.INTERACTION,
            ObjectType.ARTIFACT_REFERENCE,
            ObjectType.CONTROL_EVENT,
        }
    ),
    ActorRole.WORKBENCH: frozenset(
        {
            ObjectType.OBSERVATION,
            ObjectType.PROPOSAL,
        }
    ),
}


def authorize_operation(
    actor_role: ActorRole,
    object_type: ObjectType,
    *,
    write: bool = False,
) -> None:
    actor_role = ActorRole(actor_role)
    object_type = ObjectType(object_type)
    matrix = WRITE_PERMISSIONS if write else READ_PERMISSIONS
    if object_type not in matrix[actor_role]:
        raise PermissionError("actor cannot access this object in the requested mode")


def validate_api_contract() -> None:
    if TOP_LEVEL_SPACES != (Space.MY_WORK, Space.WORKBENCH):
        raise AssertionError("the product contract must contain exactly two top-level spaces")
    if set(OBJECT_API_ROUTES) != set(ObjectType):
        raise AssertionError("every contract object requires one versioned business API route")
    if any(not route.startswith("/api/v1/") for route in OBJECT_API_ROUTES.values()):
        raise AssertionError("all business API routes must be versioned")
    if any(
        page.business_api_prefix.startswith(("d1:", "r2:"))
        for page in PAGES
    ):
        raise AssertionError("pages must not connect directly to storage")
