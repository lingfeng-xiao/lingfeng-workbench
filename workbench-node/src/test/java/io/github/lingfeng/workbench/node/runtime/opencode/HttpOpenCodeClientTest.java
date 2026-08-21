package io.github.lingfeng.workbench.node.runtime.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOpenCodeClientTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<JsonNode> promptPayload = new AtomicReference<>();
    private HttpServer server;
    private HttpOpenCodeClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new HttpOpenCodeClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(2), objectMapper);
    }

    @AfterEach
    void stopServer() {
        client.close();
        server.stop(0);
    }

    @Test
    void usesNativeSessionPromptStatusInteractionAndAbortEndpoints() {
        assertThat(client.health()).isEqualTo(new OpenCodeClient.Health(true, "0.0.0--test"));
        OpenCodeClient.Session session = client.createSession(temporaryDirectory, "lingfeng run_001");
        client.promptAsync(temporaryDirectory, session.id(),
                new OpenCodePromptTarget("build", "workspace", "gpt-5.6-luna"), "do the mission");

        assertThat(client.sessionStatuses(temporaryDirectory)).containsEntry("ses_001", "busy");
        assertThat(client.messages(temporaryDirectory, session.id())).hasSize(1);
        assertThat(client.permissions(temporaryDirectory)).hasSize(1);
        assertThat(client.questions(temporaryDirectory)).hasSize(1);
        assertThat(client.supportsPromptTarget(
                new OpenCodePromptTarget("build", "workspace", "gpt-5.6-luna"))).isTrue();
        assertThat(client.supportsPromptTarget(
                new OpenCodePromptTarget("build", "workspace", "missing"))).isFalse();

        client.replyPermission(temporaryDirectory, "per_001", true);
        client.replyQuestion(temporaryDirectory, "que_001", "blue");
        client.rejectQuestion(temporaryDirectory, "que_002");
        client.abort(temporaryDirectory, session.id());

        assertThat(requests).anyMatch(value -> value.startsWith("POST /session/ses_001/prompt_async?directory="))
                .anyMatch(value -> value.startsWith("POST /permission/per_001/reply?directory="))
                .anyMatch(value -> value.startsWith("POST /question/que_001/reply?directory="))
                .anyMatch(value -> value.startsWith("POST /question/que_002/reject?directory="))
                .anyMatch(value -> value.startsWith("POST /session/ses_001/abort?directory="));
        assertThat(promptPayload.get().path("agent").asText()).isEqualTo("build");
        assertThat(promptPayload.get().path("model").path("providerID").asText()).isEqualTo("workspace");
        assertThat(promptPayload.get().path("model").path("modelID").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(promptPayload.get().path("parts").get(0).path("text").asText()).isEqualTo("do the mission");
    }

    @Test
    void rejectsMalformedNativeResponses() {
        assertThatThrownBy(() -> client.getSession(temporaryDirectory, "malformed"))
                .isInstanceOf(OpenCodeClientException.class)
                .hasMessageContaining("missing id");
        assertThatThrownBy(() -> client.getSession(temporaryDirectory, "missing"))
                .isInstanceOf(OpenCodeClientException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void parsesNativeSseDataFrames() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<JsonNode> event = new AtomicReference<>();
        OpenCodeClient.Subscription subscription = client.subscribe(
                temporaryDirectory,
                value -> {
                    event.set(value);
                    received.countDown();
                },
                ignored -> { });
        try {
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get().path("type").asText()).isEqualTo("server.connected");
        } finally {
            subscription.close();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        if (path.equals("/global/health")) {
            respond(exchange, 200, "{\"healthy\":true,\"version\":\"0.0.0--test\"}");
        } else if (path.equals("/session") && exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 200, "{\"id\":\"ses_001\",\"directory\":\""
                    + escape(temporaryDirectory.toAbsolutePath().normalize().toString()) + "\"}");
        } else if (path.equals("/session/status")) {
            respond(exchange, 200, "{\"ses_001\":{\"type\":\"busy\"}}");
        } else if (path.equals("/session/ses_001/message") && exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, "[{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"type\":\"text\",\"text\":\"done\"}]}]");
        } else if (path.equals("/session/ses_001/prompt_async")) {
            promptPayload.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 204, "");
        } else if (path.equals("/agent")) {
            respond(exchange, 200, "[{\"name\":\"build\"}]");
        } else if (path.equals("/provider")) {
            respond(exchange, 200, "{\"all\":[{\"id\":\"workspace\",\"models\":"
                    + "{\"gpt-5.6-luna\":{\"id\":\"gpt-5.6-luna\"}}}],\"connected\":[\"workspace\"]}");
        } else if (path.equals("/event")) {
            byte[] bytes = "data: {\"type\":\"server.connected\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } else if (path.equals("/permission") || path.equals("/question")) {
            String body = path.equals("/permission")
                    ? "[{\"id\":\"per_001\",\"sessionID\":\"ses_001\"}]"
                    : "[{\"id\":\"que_001\",\"sessionID\":\"ses_001\"}]";
            respond(exchange, 200, body);
        } else if (path.equals("/session/malformed")) {
            respond(exchange, 200, "{}");
        } else if (path.equals("/session/missing")) {
            respond(exchange, 404, "{}");
        } else if (path.startsWith("/permission/") || path.startsWith("/question/")
                || path.endsWith("/abort")) {
            respond(exchange, 200, "true");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\");
    }
}
