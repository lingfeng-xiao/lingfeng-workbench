package io.github.lingfeng.workbench.node.runtime.opencode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class HttpOpenCodeClient implements OpenCodeClient {

    private static final Set<Integer> SUCCESS = Set.of(200, 204);

    private final URI baseUri;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService streamExecutor;
    private final Set<HttpSubscription> subscriptions = ConcurrentHashMap.newKeySet();

    public HttpOpenCodeClient(
            URI baseUri, Duration connectTimeout, Duration requestTimeout, ObjectMapper objectMapper) {
        this(baseUri, requestTimeout, objectMapper, HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build());
    }

    HttpOpenCodeClient(
            URI baseUri, Duration requestTimeout, ObjectMapper objectMapper, HttpClient httpClient) {
        this.baseUri = normalizeBaseUri(baseUri);
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Health health() {
        JsonNode response = sendJson(request("global/health", null).GET().build());
        boolean healthy = response.path("healthy").asBoolean(response.path("status").asText().equals("ok"));
        String version = response.path("version").asText("");
        return new Health(healthy, version);
    }

    @Override
    public Session createSession(Path workspace, String title) {
        ObjectNode payload = objectMapper.createObjectNode().put("title", title);
        JsonNode response = sendJson(jsonRequest("session", workspace, payload).POST(
                HttpRequest.BodyPublishers.ofString(encode(payload))).build());
        return parseSession(response);
    }

    @Override
    public Session getSession(Path workspace, String sessionId) {
        return parseSession(sendJson(request("session/" + segment(sessionId), workspace).GET().build()));
    }

    @Override
    public Map<String, String> sessionStatuses(Path workspace) {
        JsonNode response = sendJson(request("session/status", workspace).GET().build());
        if (!response.isObject()) {
            throw new OpenCodeClientException("OpenCode session status response is not an object");
        }
        Map<String, String> statuses = new LinkedHashMap<>();
        response.fields().forEachRemaining(entry -> {
            String type = entry.getValue().path("type").asText();
            if (type.isBlank()) {
                throw new OpenCodeClientException("OpenCode session status is missing type");
            }
            statuses.put(entry.getKey(), type);
        });
        return Map.copyOf(statuses);
    }

    @Override
    public List<JsonNode> messages(Path workspace, String sessionId) {
        return array(sendJson(request("session/" + segment(sessionId) + "/message", workspace).GET().build()),
                "messages");
    }

    @Override
    public List<JsonNode> permissions(Path workspace) {
        return array(sendJson(request("permission", workspace).GET().build()), "permissions");
    }

    @Override
    public List<JsonNode> questions(Path workspace) {
        return array(sendJson(request("question", workspace).GET().build()), "questions");
    }

    @Override
    public boolean supportsPromptTarget(OpenCodePromptTarget target) {
        List<JsonNode> agents = array(sendJson(request("agent", null).GET().build()), "agents");
        boolean agentAvailable = agents.stream()
                .anyMatch(agent -> agent.path("name").asText().equals(target.agent()));
        JsonNode providers = sendJson(request("provider", null).GET().build());
        JsonNode allProviders = providers.path("all");
        if (!allProviders.isArray()) {
            throw new OpenCodeClientException("OpenCode providers response is missing all");
        }
        for (JsonNode provider : allProviders) {
            if (provider.path("id").asText().equals(target.providerId())) {
                JsonNode models = provider.path("models");
                if (!models.isObject()) {
                    throw new OpenCodeClientException("OpenCode provider models response is not an object");
                }
                return agentAvailable && models.has(target.modelId());
            }
        }
        return false;
    }

    @Override
    public void promptAsync(
            Path workspace, String sessionId, OpenCodePromptTarget target, String prompt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agent", target.agent());
        ObjectNode model = payload.putObject("model");
        model.put("providerID", target.providerId());
        model.put("modelID", target.modelId());
        ArrayNode parts = payload.putArray("parts");
        parts.addObject().put("type", "text").put("text", prompt);
        sendEmpty(jsonRequest("session/" + segment(sessionId) + "/prompt_async", workspace, payload)
                .POST(HttpRequest.BodyPublishers.ofString(encode(payload))).build());
    }

    @Override
    public void replyPermission(Path workspace, String requestId, boolean approved) {
        ObjectNode payload = objectMapper.createObjectNode().put("reply", approved ? "once" : "reject");
        sendJson(jsonRequest("permission/" + segment(requestId) + "/reply", workspace, payload)
                .POST(HttpRequest.BodyPublishers.ofString(encode(payload))).build());
    }

    @Override
    public void replyQuestion(Path workspace, String requestId, String answer) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("answers").addArray().add(answer);
        sendJson(jsonRequest("question/" + segment(requestId) + "/reply", workspace, payload)
                .POST(HttpRequest.BodyPublishers.ofString(encode(payload))).build());
    }

    @Override
    public void rejectQuestion(Path workspace, String requestId) {
        sendJson(request("question/" + segment(requestId) + "/reject", workspace)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    @Override
    public void abort(Path workspace, String sessionId) {
        sendJson(request("session/" + segment(sessionId) + "/abort", workspace)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    @Override
    public Subscription subscribe(
            Path workspace, Consumer<JsonNode> eventSink, Consumer<Throwable> failureSink) {
        HttpRequest request = request("event", workspace)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        CompletableFuture<HttpResponse<InputStream>> response = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        HttpSubscription subscription = new HttpSubscription(response);
        subscriptions.add(subscription);
        response.whenComplete((received, failure) -> {
            if (failure != null) {
                subscriptions.remove(subscription);
                failureSink.accept(failure);
                return;
            }
            if (received.statusCode() != 200) {
                subscriptions.remove(subscription);
                closeQuietly(received.body());
                failureSink.accept(new OpenCodeClientException(
                        "OpenCode SSE returned HTTP " + received.statusCode()));
                return;
            }
            String contentType = received.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase().startsWith("text/event-stream")) {
                subscriptions.remove(subscription);
                closeQuietly(received.body());
                failureSink.accept(new OpenCodeClientException(
                        "OpenCode event endpoint did not return SSE"));
                return;
            }
            subscription.setStream(received.body());
            streamExecutor.submit(() -> readEvents(subscription, received.body(), eventSink, failureSink));
        });
        return subscription;
    }

    @Override
    public void close() {
        subscriptions.forEach(HttpSubscription::close);
        subscriptions.clear();
        streamExecutor.close();
    }

    private void readEvents(
            HttpSubscription subscription,
            InputStream stream,
            Consumer<JsonNode> eventSink,
            Consumer<Throwable> failureSink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while (!subscription.closed.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emitData(data, eventSink);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            emitData(data, eventSink);
            if (!subscription.closed.get()) {
                failureSink.accept(new OpenCodeClientException("OpenCode SSE ended unexpectedly"));
            }
        } catch (IOException | RuntimeException exception) {
            if (!subscription.closed.get()) {
                failureSink.accept(exception);
            }
        } finally {
            subscriptions.remove(subscription);
        }
    }

    private void emitData(StringBuilder data, Consumer<JsonNode> eventSink) {
        if (data.isEmpty()) {
            return;
        }
        String value = data.toString();
        data.setLength(0);
        try {
            eventSink.accept(objectMapper.readTree(value));
        } catch (JsonProcessingException exception) {
            throw new OpenCodeClientException("OpenCode SSE contained malformed JSON", exception);
        }
    }

    private JsonNode sendJson(HttpRequest request) {
        HttpResponse<String> response = send(request);
        if (response.body() == null || response.body().isBlank()) {
            if (response.statusCode() == 204) {
                return objectMapper.nullNode();
            }
            throw new OpenCodeClientException("OpenCode returned an empty JSON response");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new OpenCodeClientException("OpenCode returned malformed JSON", exception);
        }
    }

    private void sendEmpty(HttpRequest request) {
        send(request);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!SUCCESS.contains(response.statusCode())) {
                throw new OpenCodeClientException("OpenCode returned HTTP " + response.statusCode());
            }
            return response;
        } catch (IOException exception) {
            throw new OpenCodeClientException("OpenCode request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenCodeClientException("OpenCode request was interrupted", exception);
        }
    }

    private HttpRequest.Builder request(String relativePath, Path workspace) {
        String query = workspace == null ? "" : "?directory=" + encode(workspace.toAbsolutePath().normalize().toString());
        return HttpRequest.newBuilder(baseUri.resolve(relativePath + query)).timeout(requestTimeout);
    }

    private HttpRequest.Builder jsonRequest(String relativePath, Path workspace, JsonNode payload) {
        return request(relativePath, workspace).header("Content-Type", "application/json");
    }

    private Session parseSession(JsonNode response) {
        String id = response.path("id").asText();
        if (id.isBlank()) {
            throw new OpenCodeClientException("OpenCode Session response is missing id");
        }
        return new Session(id, response.path("directory").asText(""));
    }

    private List<JsonNode> array(JsonNode response, String label) {
        if (!response.isArray()) {
            throw new OpenCodeClientException("OpenCode " + label + " response is not an array");
        }
        List<JsonNode> values = new ArrayList<>();
        response.forEach(values::add);
        return List.copyOf(values);
    }

    private String encode(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new OpenCodeClientException("Unable to encode OpenCode request", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String segment(String value) {
        return encode(value);
    }

    private static URI normalizeBaseUri(URI baseUri) {
        if (baseUri == null || baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("OpenCode base URI must be absolute");
        }
        String text = baseUri.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static final class HttpSubscription implements Subscription {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CompletableFuture<HttpResponse<InputStream>> response;
        private volatile InputStream stream;

        private HttpSubscription(CompletableFuture<HttpResponse<InputStream>> response) {
            this.response = response;
        }

        private void setStream(InputStream stream) {
            this.stream = stream;
            if (closed.get()) {
                closeQuietly(stream);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                response.cancel(true);
                InputStream active = stream;
                if (active != null) {
                    closeQuietly(active);
                }
            }
        }
    }
}
