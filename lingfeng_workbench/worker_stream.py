"""Hermes-side stdin/stdout protocol endpoint used by forced SSH commands."""

from __future__ import annotations

import logging
import sys
from typing import TextIO

from .domain import InteractionKind, SideEffectState, compact_summary
from .protocol import (
    PROTOCOL_VERSION,
    Correlation,
    Envelope,
    MessageType,
    response_envelope,
)
from .service import LingfengService
from .storage import PluginStore


logger = logging.getLogger(__name__)


class WorkerStream:
    """Translate protocol envelopes into idempotent application-service calls."""

    def __init__(
        self,
        service: LingfengService,
        store: PluginStore,
        *,
        expected_node_id: str | None = None,
    ) -> None:
        self.service = service
        self.store = store
        self.expected_node_id = expected_node_id

    def process(self, request: Envelope) -> Envelope:
        try:
            return self._process(request)
        except (KeyError, LookupError, TypeError, ValueError, RuntimeError) as exc:
            return response_envelope(
                request,
                MessageType.ERROR,
                {
                    "code": "request_rejected",
                    "message": compact_summary(str(exc), field_name="error"),
                },
            )

    def _process(self, request: Envelope) -> Envelope:
        if self.expected_node_id and request.node_id != self.expected_node_id:
            raise ValueError("node_id does not match the SSH command binding")
        message_type = request.message_type
        if message_type is MessageType.HELLO:
            self._require_payload_fields(
                request,
                required={"display_name", "runtime_capabilities"},
            )
            capabilities = request.payload["runtime_capabilities"]
            if not isinstance(capabilities, dict):
                raise ValueError("runtime_capabilities must be an object")
            self.store.register_node(
                node_id=request.node_id,
                display_name=compact_summary(
                    request.payload["display_name"],
                    field_name="display_name",
                ),
                protocol_version=PROTOCOL_VERSION,
                runtime_capabilities=capabilities,
            )
            return response_envelope(
                request,
                MessageType.WELCOME,
                {"protocol_version": PROTOCOL_VERSION, "heartbeat_seconds": 30},
            )

        if message_type is MessageType.HEARTBEAT:
            self._require_payload_fields(request)
            if not self.store.heartbeat_node(request.node_id):
                raise LookupError("node must send HELLO before HEARTBEAT")
            interaction = self.store.take_resolved_interaction_for_node(
                request.node_id
            )
            if interaction is not None:
                return response_envelope(
                    request,
                    MessageType.INTERACTION_RESPONSE,
                    {
                        "interaction_id": interaction["interaction_id"],
                        "checkpoint_id": interaction["checkpoint_id"],
                        "response_summary": interaction["response_summary"],
                    },
                    correlation=Correlation(run_id=interaction["run_id"]),
                )
            return response_envelope(request, MessageType.ACK)

        if message_type is MessageType.CLAIM_REQUEST:
            self._require_payload_fields(request)
            assignment = self.service.claim_next_mission(request.node_id)
            if assignment is None:
                return response_envelope(request, MessageType.NO_MISSION)
            return response_envelope(
                request,
                MessageType.MISSION_ASSIGNED,
                assignment.to_payload(),
                correlation=Correlation(
                    work_item_id=assignment.work_item_id,
                    mission_id=assignment.mission_id,
                    run_id=assignment.run_id,
                ),
            )

        if message_type not in {
            MessageType.RUN_STARTED,
            MessageType.RUN_EVENT,
            MessageType.INTERACTION_REQUIRED,
            MessageType.RUN_COMPLETED,
            MessageType.RUN_FAILED,
            MessageType.RUN_INTERRUPTED,
        }:
            raise ValueError(f"message type is not valid from a node: {message_type}")

        run_id = self._required_run_id(request)
        if self.store.has_control_event(request.message_id):
            return response_envelope(
                request,
                MessageType.ACK,
                {"duplicate": True},
            )

        response_payload: dict[str, object] = {}
        summary: str | None = None
        if message_type is MessageType.RUN_STARTED:
            self._require_payload_fields(
                request,
                allowed={"runtime_session_ref"},
            )
            self.service.record_run_started(
                run_id,
                runtime_session_ref=request.payload.get("runtime_session_ref"),
            )
        elif message_type is MessageType.RUN_EVENT:
            self._require_payload_fields(
                request,
                required={"event_type", "summary"},
                allowed={"event_type", "summary", "checkpoint_id"},
            )
            if request.payload["event_type"] not in {"PROGRESS", "CHECKPOINT"}:
                raise ValueError("RUN_EVENT event_type must be PROGRESS or CHECKPOINT")
            summary = request.payload["summary"]
            self.service.record_progress(
                run_id,
                summary,
                checkpoint_id=request.payload.get("checkpoint_id"),
                notify_checkpoint=request.payload["event_type"] == "CHECKPOINT",
            )
        elif message_type is MessageType.INTERACTION_REQUIRED:
            self._require_payload_fields(
                request,
                required={"checkpoint_id", "kind", "prompt_summary"},
                allowed={
                    "checkpoint_id",
                    "kind",
                    "prompt_summary",
                    "options",
                    "risk_summary",
                    "expires_at",
                },
            )
            interaction_id = self.service.create_interaction(
                run_id,
                checkpoint_id=request.payload["checkpoint_id"],
                kind=InteractionKind(request.payload["kind"]),
                prompt_summary=request.payload["prompt_summary"],
                options=request.payload.get("options"),
                risk_summary=request.payload.get("risk_summary"),
                expires_at=request.payload.get("expires_at"),
            )
            summary = request.payload["prompt_summary"]
            response_payload["interaction_id"] = interaction_id
        elif message_type is MessageType.RUN_COMPLETED:
            self._require_payload_fields(request, required={"result_summary"})
            summary = request.payload["result_summary"]
            self.service.complete_run(run_id, summary)
        else:
            self._require_payload_fields(
                request,
                required={"reason"},
                allowed={"reason", "side_effect_state"},
            )
            summary = request.payload["reason"]
            self.service.fail_run(
                run_id,
                summary,
                interrupted=message_type is MessageType.RUN_INTERRUPTED,
                side_effect_state=SideEffectState(
                    request.payload.get("side_effect_state", "none")
                ),
            )

        self.store.record_control_event_once(
            message_id=request.message_id,
            entity_type="run",
            entity_id=run_id,
            event_type=message_type.value,
            summary=compact_summary(summary, field_name="event_summary")
            if summary
            else None,
        )
        return response_envelope(request, MessageType.ACK, response_payload)

    @staticmethod
    def _required_run_id(request: Envelope) -> str:
        if request.correlation.run_id is None:
            raise ValueError("run_id correlation is required")
        return request.correlation.run_id

    @staticmethod
    def _require_payload_fields(
        request: Envelope,
        *,
        required: set[str] | None = None,
        allowed: set[str] | None = None,
    ) -> None:
        required_fields = required or set()
        allowed_fields = allowed if allowed is not None else required_fields
        missing = sorted(required_fields.difference(request.payload))
        if missing:
            raise ValueError(f"payload missing fields: {', '.join(missing)}")
        unknown = sorted(set(request.payload).difference(allowed_fields))
        if unknown:
            raise ValueError(f"payload contains unknown fields: {', '.join(unknown)}")


def run_worker_stream(
    worker: WorkerStream,
    *,
    input_stream: TextIO = sys.stdin,
    output_stream: TextIO = sys.stdout,
) -> int:
    """Run until stdin closes; stdout is reserved exclusively for NDJSON."""
    for raw_line in input_stream:
        raw = raw_line.rstrip("\r\n")
        if not raw:
            continue
        try:
            request = Envelope.from_json(raw)
            response = worker.process(request)
        except ValueError as exc:
            logger.warning("rejected malformed worker message: %s", exc)
            response = Envelope(
                message_type=MessageType.ERROR,
                node_id="unknown-node",
                payload={
                    "code": "malformed_envelope",
                    "message": compact_summary(str(exc), field_name="error"),
                },
            )
        output_stream.write(response.to_json() + "\n")
        output_stream.flush()
    return 0
