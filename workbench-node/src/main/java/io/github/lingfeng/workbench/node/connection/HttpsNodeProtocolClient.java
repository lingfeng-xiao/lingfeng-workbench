package io.github.lingfeng.workbench.node.connection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import io.github.lingfeng.workbench.node.protocol.PollResponse;
import io.github.lingfeng.workbench.node.protocol.ProtocolAck;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HttpsNodeProtocolClient implements NodeProtocolClient {

    static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final String PROTOCOL_VERSION = "1.0";

    private final NodeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    public HttpsNodeProtocolClient(NodeProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(properties, objectMapper, httpClient, Clock.systemUTC());
    }

    HttpsNodeProtocolClient(
            NodeProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public ProtocolAck hello(Set<String> capabilities) {
        ObjectNode request = newHeader();
        request.put("displayName", properties.displayName());
        ArrayNode capabilityArray = request.putArray("capabilities");
        capabilities.stream().sorted().forEach(capabilityArray::add);
        return postAck("hello", request);
    }

    @Override
    public ProtocolAck heartbeat(String activeRunId) {
        ObjectNode request = newHeader();
        if (activeRunId != null) {
            request.put("activeRunId", activeRunId);
        }
        return postAck("heartbeat", request);
    }

    @Override
    public PollResponse poll(List<String> acknowledgedCommandIds) {
        ObjectNode request = newHeader();
        ArrayNode acknowledged = request.putArray("acknowledgedCommandIds");
        acknowledgedCommandIds.forEach(acknowledged::add);
        JsonNode response = post("poll", request);
        requireOnly(response, Set.of(
                "commandType", "commandId", "workItemId", "missionId", "runId", "missionRevision",
                "missionDigest", "objective", "acceptanceSummary", "authorizedSideEffectsSummary",
                "targetNodeId", "workspaceRef", "runtimeKind", "executionProfile"));
        String commandType = requiredText(response, "commandType");
        if ("NO_COMMAND".equals(commandType)) {
            if (response.size() != 1) {
                throw new ProtocolClientException("NO_COMMAND contains unexpected fields", false);
            }
            return new PollResponse.NoCommand();
        }
        if (!"ASSIGNMENT".equals(commandType)) {
            throw new ProtocolClientException("Unsupported commandType: " + commandType, false);
        }
        Assignment assignment = new Assignment(
                requiredText(response, "commandId"),
                requiredText(response, "workItemId"),
                requiredText(response, "missionId"),
                requiredText(response, "runId"),
                requiredPositiveInteger(response, "missionRevision"),
                requiredText(response, "missionDigest"),
                requiredText(response, "objective"),
                requiredText(response, "acceptanceSummary"),
                requiredText(response, "authorizedSideEffectsSummary"),
                requiredText(response, "targetNodeId"),
                requiredText(response, "workspaceRef"),
                requiredText(response, "runtimeKind"),
                requiredText(response, "executionProfile"));
        return new PollResponse.AssignmentCommand(assignment);
    }

    @Override
    public ProtocolAck sendEvent(OutboundEvent event) {
        return postAck("events", event.payload());
    }

    private ProtocolAck postAck(String path, JsonNode request) {
        JsonNode response = post(path, request);
        requireOnly(response, Set.of("requestMessageId", "duplicate"));
        JsonNode duplicate = response.get("duplicate");
        if (duplicate == null || !duplicate.isBoolean()) {
            throw new ProtocolClientException("Acknowledgement duplicate must be boolean", false);
        }
        String requestMessageId = requiredText(response, "requestMessageId");
        String sentMessageId = requiredText(request, "messageId");
        if (!sentMessageId.equals(requestMessageId)) {
            throw new ProtocolClientException("Service acknowledged a different message", false);
        }
        return new ProtocolAck(requestMessageId, duplicate.booleanValue());
    }

    private JsonNode post(String path, JsonNode request) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new ProtocolClientException("Unable to encode protocol request", false, exception);
        }
        requireMessageSize(body);
        URI endpoint = properties.serviceBaseUri().resolve("api/node/v1/" + path);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.bearerToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProtocolClientException("Protocol request interrupted", true, exception);
        } catch (IOException exception) {
            throw new ProtocolClientException("Protocol request failed", true, exception);
        }
        requireMessageSize(response.body());
        if (response.statusCode() != 200) {
            boolean retryable = response.statusCode() == 408 || response.statusCode() == 429
                    || response.statusCode() >= 500;
            throw new ProtocolClientException("Service rejected protocol request with status "
                    + response.statusCode(), retryable);
        }
        try {
            JsonNode decoded = objectMapper.readTree(response.body());
            if (decoded == null || !decoded.isObject()) {
                throw new ProtocolClientException("Protocol response must be a JSON object", false);
            }
            return decoded;
        } catch (JsonProcessingException exception) {
            throw new ProtocolClientException("Service returned invalid JSON", false, exception);
        } catch (IOException exception) {
            throw new ProtocolClientException("Unable to read service response", true, exception);
        }
    }

    private ObjectNode newHeader() {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("protocolVersion", PROTOCOL_VERSION);
        header.put("messageId", "msg-" + UUID.randomUUID());
        header.put("nodeId", properties.nodeId());
        header.put("sentAt", Instant.now(clock).toString());
        return header;
    }

    private static void requireMessageSize(byte[] message) {
        if (message.length > MAX_MESSAGE_BYTES) {
            throw new ProtocolClientException("Protocol message exceeds 64 KiB", false);
        }
    }

    private static String requiredText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ProtocolClientException("Missing text field: " + fieldName, false);
        }
        return value.textValue();
    }

    private static int requiredPositiveInteger(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.canConvertToInt() || value.intValue() < 1) {
            throw new ProtocolClientException("Missing positive integer field: " + fieldName, false);
        }
        return value.intValue();
    }

    private static void requireOnly(JsonNode object, Set<String> allowedFields) {
        Set<String> unknownFields = new HashSet<>();
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unknownFields.add(field);
            }
        });
        if (!unknownFields.isEmpty()) {
            throw new ProtocolClientException("Protocol response contains unknown fields: " + unknownFields, false);
        }
    }
}
