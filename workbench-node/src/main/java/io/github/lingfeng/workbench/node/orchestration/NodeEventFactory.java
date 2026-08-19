package io.github.lingfeng.workbench.node.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class NodeEventFactory {

    private static final String PROTOCOL_VERSION = "1.0";

    private final NodeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeEventFactory(NodeProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OutboundEvent runAccepted(Assignment assignment) {
        ObjectNode event = runEventHeader(assignment, "RUN_ACCEPTED");
        event.put("commandId", assignment.commandId());
        return outboundEvent(assignment.runId(), "RUN_ACCEPTED", event);
    }

    public OutboundEvent runStarted(Assignment assignment, boolean resumable) {
        ObjectNode event = runEventHeader(assignment, "RUN_STARTED");
        event.put("resumable", resumable);
        return outboundEvent(assignment.runId(), "RUN_STARTED", event);
    }

    public OutboundEvent progress(Assignment assignment, String summary) {
        ObjectNode event = runEventHeader(assignment, "PROGRESS");
        event.put("progressSummary", compactSummary(summary));
        return outboundEvent(assignment.runId(), "PROGRESS", event);
    }

    public OutboundEvent terminal(
            Assignment assignment,
            String eventType,
            RuntimeEvent.RuntimeOutcome runtimeOutcome,
            RuntimeEvent.AcceptanceStatus acceptanceStatus,
            String summary) {
        ObjectNode event = runEventHeader(assignment, eventType);
        event.put("runtimeOutcome", runtimeOutcome.name());
        event.put("acceptanceStatus", acceptanceStatus.name());
        event.put("resultSummary", compactSummary(summary));
        return outboundEvent(assignment.runId(), eventType, event);
    }

    private ObjectNode runEventHeader(Assignment assignment, String eventType) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocolVersion", PROTOCOL_VERSION);
        event.put("messageId", newMessageId());
        event.put("nodeId", properties.nodeId());
        event.put("sentAt", Instant.now(clock).toString());
        event.put("eventType", eventType);
        event.put("workItemId", assignment.workItemId());
        event.put("missionId", assignment.missionId());
        event.put("runId", assignment.runId());
        event.put("missionDigest", assignment.missionDigest());
        return event;
    }

    private OutboundEvent outboundEvent(String runId, String eventType, ObjectNode event) {
        return new OutboundEvent(event.path("messageId").asText(), runId, eventType, event);
    }

    private static String newMessageId() {
        return "msg-" + UUID.randomUUID();
    }

    private static String compactSummary(String source) {
        String compact = source == null ? "" : source.trim().replaceAll("\\s+", " ");
        if (compact.isEmpty()) {
            compact = "No summary was provided";
        }
        return compact.length() <= 800 ? compact : compact.substring(0, 797) + "...";
    }
}
