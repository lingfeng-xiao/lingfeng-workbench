package io.github.lingfeng.workbench.node.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

public record DurableNodeEvent(long outboxSequence, String messageId, String runId, String eventType, JsonNode payload) {
}
