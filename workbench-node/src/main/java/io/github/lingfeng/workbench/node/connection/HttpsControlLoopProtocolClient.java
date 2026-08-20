package io.github.lingfeng.workbench.node.connection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolAck;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.protocol.v2.PollResult;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolValidation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class HttpsControlLoopProtocolClient implements ControlLoopProtocolClient {

    private final NodeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    public HttpsControlLoopProtocolClient(
            NodeProperties properties, ObjectMapper objectMapper, HttpClient httpClient, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public ProtocolAck hello(Set<String> capabilities) {
        ObjectNode request = header();
        request.put("displayName", properties.displayName());
        ArrayNode values = request.putArray("capabilities");
        capabilities.stream().sorted().forEach(values::add);
        return postAck("hello", request);
    }

    @Override
    public ProtocolAck heartbeat(String activeRunId, String activeRunState) {
        ObjectNode request = header();
        if (activeRunId != null) {
            request.put("activeRunId", activeRunId);
            request.put("activeRunState", activeRunState);
        }
        return postAck("heartbeat", request);
    }

    @Override
    public PollResult poll() {
        ObjectNode request = header();
        request.put("maxCommands", 1);
        JsonNode response = post("poll", request);
        if (response.path("commandAvailable").isBoolean()
                && !response.path("commandAvailable").booleanValue()) {
            ProtocolValidation.requireOnly(response, Set.of("commandAvailable"));
            return new PollResult.NoCommand();
        }
        NodeCommand command = ProtocolValidation.parseCommand(response, properties.nodeId());
        return new PollResult.Command(command);
    }

    @Override
    public ProtocolAck sendEvent(DurableNodeEvent event) {
        return postAck("events", event.payload());
    }

    private ProtocolAck postAck(String path, JsonNode request) {
        JsonNode response = post(path, request);
        ProtocolValidation.requireOnly(response, Set.of("requestMessageId", "duplicate"));
        String requestMessageId = ProtocolValidation.identifier(response, "requestMessageId");
        JsonNode duplicate = response.get("duplicate");
        if (duplicate == null || !duplicate.isBoolean()) {
            throw new ProtocolClientException("Acknowledgement duplicate must be boolean", false);
        }
        if (!request.path("messageId").asText().equals(requestMessageId)) {
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
        ProtocolValidation.requireMessageSize(body);
        URI endpoint = properties.serviceBaseUri().resolve("api/node/v2/" + path);
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
            throw new ProtocolClientException(classifyTransportFailure(exception), true, exception);
        }
        ProtocolValidation.requireMessageSize(response.body());
        if (response.statusCode() != 200) {
            throw rejectedStatus(response.statusCode());
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
            throw new ProtocolClientException("Unable to read Service response", true, exception);
        }
    }

    private ObjectNode header() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("protocolVersion", "2.0");
        request.put("messageId", "msg-" + UUID.randomUUID());
        request.put("nodeId", properties.nodeId());
        request.put("sentAt", Instant.now(clock).toString());
        return request;
    }

    static ProtocolClientException rejectedStatus(int statusCode) {
        boolean retryable = statusCode == 407 || statusCode == 408 || statusCode == 429 || statusCode >= 500;
        String category = switch (statusCode) {
            case 401 -> "authentication";
            case 403 -> "node scope";
            case 407 -> "proxy authentication";
            default -> "HTTP " + statusCode;
        };
        return new ProtocolClientException("Service preflight/protocol failure: " + category, retryable);
    }

    static String classifyTransportFailure(IOException exception) {
        String type = exception.getClass().getSimpleName().toLowerCase();
        String message = String.valueOf(exception.getMessage()).toLowerCase();
        if (type.contains("unknownhost") || message.contains("unresolved")) {
            return "DNS preflight failed";
        }
        if (type.contains("ssl") || message.contains("certificate") || message.contains("handshake")) {
            return "TLS preflight failed";
        }
        if (message.contains("proxy")) {
            return "Proxy preflight failed";
        }
        return "Service connection failed";
    }
}
