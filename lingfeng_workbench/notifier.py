"""High-value control-plane notifications routed through Hermes messaging."""

from __future__ import annotations

import json
import logging


logger = logging.getLogger(__name__)


class HermesMessageNotifier:
    """Send short messages using Hermes' supported standalone sender."""

    def __init__(self, target: str = "weixin") -> None:
        self.target = target.strip()

    def notify(self, event_type: str, message: str) -> None:
        if not self.target:
            return
        try:
            from tools.send_message_tool import send_message_tool

            raw = send_message_tool(
                {
                    "action": "send",
                    "target": self.target,
                    "message": message,
                }
            )
            decoded = json.loads(raw) if isinstance(raw, str) else raw
            if isinstance(decoded, dict) and decoded.get("error"):
                logger.warning(
                    "lingfeng notification failed event=%s: %s",
                    event_type,
                    decoded["error"],
                )
        except Exception as exc:  # Notification failure must not corrupt a run.
            logger.warning(
                "lingfeng notification failed event=%s: %s",
                event_type,
                exc,
            )
