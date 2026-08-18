"""Hermes agent tool schemas."""

from __future__ import annotations

from typing import Any


CREATE_WORK_ITEM_SCHEMA: dict[str, Any] = {
    "name": "lingfeng_create_work_item",
    "description": (
        "Create a lingfeng-workbench WorkItem and its first office-PC Mission. "
        "Only short control summaries are stored on Hermes. Full requirements, "
        "company code, logs, credentials, reports, and runtime conversations must "
        "remain on the office computer. Ask the user to acknowledge this boundary."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "title": {"type": "string", "maxLength": 800},
            "objective": {"type": "string", "maxLength": 800},
            "acceptance_summary": {"type": "string", "maxLength": 800},
            "kind": {
                "type": "string",
                "enum": ["demand", "bug", "task", "investigation"],
                "default": "task",
            },
            "priority": {"type": "integer", "minimum": -100, "maximum": 100},
            "target_node_id": {"type": "string", "default": "office-pc"},
            "local_workspace_ref": {
                "type": "string",
                "description": "Opaque allow-listed local workspace name, never a path.",
                "default": "office-default",
            },
            "interaction_policy": {
                "type": "string",
                "enum": ["ask_when_blocked", "approve_after_session_start"],
                "default": "ask_when_blocked",
                "description": (
                    "Use approve_after_session_start to establish a WS Session, "
                    "pause before effects, and resume it after one phone approval."
                ),
            },
            "authorized_side_effects_summary": {
                "type": "string",
                "maxLength": 800,
                "default": "none",
            },
            "data_boundary_ack": {
                "type": "boolean",
                "description": "True only after the user accepts the data-boundary notice.",
            },
            "idempotency_key": {"type": "string"},
        },
        "required": [
            "title",
            "objective",
            "acceptance_summary",
            "data_boundary_ack",
            "idempotency_key",
        ],
        "additionalProperties": False,
    },
}


STATUS_SCHEMA: dict[str, Any] = {
    "name": "lingfeng_status",
    "description": (
        "Return connected office nodes, recent runs, pending interactions, and "
        "short task counts from lingfeng-workbench."
    ),
    "parameters": {
        "type": "object",
        "properties": {},
        "additionalProperties": False,
    },
}


RESOLVE_INTERACTION_SCHEMA: dict[str, Any] = {
    "name": "lingfeng_resolve_interaction",
    "description": (
        "Resolve one pending lingfeng-workbench clarification or approval. "
        "Each interaction can be consumed exactly once."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "interaction_id": {"type": "string"},
            "response_summary": {"type": "string", "maxLength": 800},
        },
        "required": ["interaction_id", "response_summary"],
        "additionalProperties": False,
    },
}
