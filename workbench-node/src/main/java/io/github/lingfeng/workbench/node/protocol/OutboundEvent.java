package io.github.lingfeng.workbench.node.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record OutboundEvent(String messageId, String runId, String eventType, JsonNode payload) {
}
