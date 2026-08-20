package io.github.lingfeng.workbench.node.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lingfeng.workbench.node.connection.ProtocolClientException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProtocolValidation {

    public static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern WORK_ITEM_ID = Pattern.compile("^wi_[A-Za-z0-9]+$");
    private static final Pattern MISSION_ID = Pattern.compile("^mi_[A-Za-z0-9]+$");
    private static final Pattern RUN_ID = Pattern.compile("^run_[A-Za-z0-9]+$");
    private static final Pattern INTERACTION_ID = Pattern.compile("^int_[A-Za-z0-9]+$");
    private static final Pattern DIGEST = Pattern.compile("^[a-f0-9]{64}$");

    private ProtocolValidation() {
    }

    public static NodeCommand parseCommand(JsonNode payload, String expectedNodeId) {
        requireObject(payload);
        requireTextEquals(payload, "protocolVersion", "2.0");
        String messageId = identifier(payload, "messageId");
        String nodeId = identifier(payload, "nodeId");
        String targetNodeId = identifier(payload, "targetNodeId");
        if (!expectedNodeId.equals(nodeId) || !expectedNodeId.equals(targetNodeId)) {
            throw rejected("Command targets another Node");
        }
        Instant sentAt = timestamp(payload, "sentAt");
        if (!requiredBoolean(payload, "commandAvailable")) {
            throw rejected("Command payload must set commandAvailable=true");
        }
        String commandId = identifier(payload, "commandId");
        RunBinding binding = new RunBinding(
                patterned(payload, "workItemId", WORK_ITEM_ID),
                patterned(payload, "missionId", MISSION_ID),
                patterned(payload, "runId", RUN_ID),
                patterned(payload, "missionDigest", DIGEST));
        String commandType = requiredText(payload, "commandType");
        return switch (commandType) {
            case "START_RUN" -> parseStart(payload, messageId, commandId, nodeId, targetNodeId, sentAt, binding);
            case "PROVIDE_INTERACTION_RESPONSE" -> parseResponse(
                    payload, messageId, commandId, nodeId, targetNodeId, sentAt, binding);
            case "CANCEL_RUN" -> parseCancel(payload, messageId, commandId, nodeId, targetNodeId, sentAt, binding);
            default -> throw rejected("Unsupported commandType: " + commandType);
        };
    }

    public static void requireMessageSize(byte[] message) {
        if (message == null || message.length > MAX_MESSAGE_BYTES) {
            throw rejected("Protocol message exceeds 64 KiB");
        }
    }

    public static String shortText(JsonNode object, String fieldName) {
        String value = requiredText(object, fieldName);
        if (value.length() > 800) {
            throw rejected(fieldName + " exceeds 800 characters");
        }
        return value;
    }

    public static String identifier(JsonNode object, String fieldName) {
        return patterned(object, fieldName, IDENTIFIER);
    }

    public static void requireOnly(JsonNode object, Set<String> allowedFields) {
        Set<String> unknown = new HashSet<>();
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw rejected("Protocol object contains unknown fields: " + unknown);
        }
    }

    private static NodeCommand parseStart(
            JsonNode payload,
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding) {
        requireOnly(payload, Set.of(
                "protocolVersion", "messageId", "nodeId", "sentAt", "commandAvailable", "commandType",
                "commandId", "targetNodeId", "workItemId", "missionId", "runId", "missionDigest",
                "missionRevision", "objective", "acceptanceSummary", "authorizedSideEffectsSummary",
                "workspaceRef", "runtimeKind", "executionProfile"));
        JsonNode revision = payload.get("missionRevision");
        if (revision == null || !revision.canConvertToInt() || revision.intValue() < 1) {
            throw rejected("missionRevision must be a positive integer");
        }
        return new NodeCommand.StartRun(
                messageId, commandId, nodeId, targetNodeId, sentAt, binding, revision.intValue(),
                shortText(payload, "objective"), shortText(payload, "acceptanceSummary"),
                shortText(payload, "authorizedSideEffectsSummary"), identifier(payload, "workspaceRef"),
                identifier(payload, "runtimeKind"), identifier(payload, "executionProfile"), payload.deepCopy());
    }

    private static NodeCommand parseResponse(
            JsonNode payload,
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding) {
        requireOnly(payload, Set.of(
                "protocolVersion", "messageId", "nodeId", "sentAt", "commandAvailable", "commandType",
                "commandId", "targetNodeId", "workItemId", "missionId", "runId", "missionDigest",
                "interactionId", "checkpointId", "decision", "responseSummary", "resolvedAt"));
        NodeCommand.Decision decision;
        try {
            decision = NodeCommand.Decision.valueOf(requiredText(payload, "decision"));
        } catch (IllegalArgumentException exception) {
            throw rejected("Unsupported interaction decision");
        }
        return new NodeCommand.InteractionResponse(
                messageId, commandId, nodeId, targetNodeId, sentAt, binding,
                patterned(payload, "interactionId", INTERACTION_ID), identifier(payload, "checkpointId"), decision,
                shortText(payload, "responseSummary"), timestamp(payload, "resolvedAt"), payload.deepCopy());
    }

    private static NodeCommand parseCancel(
            JsonNode payload,
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding) {
        requireOnly(payload, Set.of(
                "protocolVersion", "messageId", "nodeId", "sentAt", "commandAvailable", "commandType",
                "commandId", "targetNodeId", "workItemId", "missionId", "runId", "missionDigest",
                "reasonSummary"));
        return new NodeCommand.CancelRun(messageId, commandId, nodeId, targetNodeId, sentAt, binding,
                shortText(payload, "reasonSummary"), payload.deepCopy());
    }

    private static String patterned(JsonNode object, String fieldName, Pattern pattern) {
        String value = requiredText(object, fieldName);
        if (!pattern.matcher(value).matches()) {
            throw rejected(fieldName + " has invalid format");
        }
        return value;
    }

    private static Instant timestamp(JsonNode object, String fieldName) {
        try {
            return Instant.parse(requiredText(object, fieldName));
        } catch (DateTimeParseException exception) {
            throw rejected(fieldName + " must be an RFC 3339 timestamp");
        }
    }

    private static String requiredText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw rejected("Missing text field: " + fieldName);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isBoolean()) {
            throw rejected("Missing boolean field: " + fieldName);
        }
        return value.booleanValue();
    }

    private static void requireTextEquals(JsonNode object, String fieldName, String expected) {
        if (!expected.equals(requiredText(object, fieldName))) {
            throw rejected(fieldName + " is unsupported");
        }
    }

    private static void requireObject(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw rejected("Protocol response must be a JSON object");
        }
    }

    private static ProtocolClientException rejected(String message) {
        return new ProtocolClientException(message, false);
    }
}
