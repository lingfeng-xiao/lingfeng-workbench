package io.github.lingfeng.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchServiceIntegrationTest {
    private static final String HERMES_TOKEN = "hermes-test-token-000000000000000000000000";
    private static final String SITES_TOKEN = "sites-test-token-0000000000000000000000000";
    private static final String NODE_TOKEN = "node-a-test-token-000000000000000000000000";
    private static final Path DATABASE = Path.of("target", "integration-" + UUID.randomUUID() + ".db");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
        registry.add("workbench.security.hermes-token", () -> HERMES_TOKEN);
        registry.add("workbench.security.sites-token", () -> SITES_TOKEN);
        registry.add("workbench.security.node-tokens.node-a", () -> NODE_TOKEN);
    }

    @Test
    @Order(1)
    void completesMissionOnlyAfterExplicitPassedTerminalAndKeepsSensitiveFieldsOut() throws Exception {
        JsonNode created = createWorkItem("create-passed", "node-a");
        String workItemId = created.get("workItemId").asText();
        String missionId = created.get("missionId").asText();
        String digest = created.get("missionDigest").asText();

        hello("hello-passed", "node-a", NODE_TOKEN);
        JsonNode assignment = poll("poll-passed", "node-a", NODE_TOKEN);
        String runId = assignment.get("runId").asText();
        String commandId = assignment.get("commandId").asText();
        assertThat(assignment.get("missionDigest").asText()).isEqualTo(digest);

        nodeEvent("accepted-passed", "RUN_ACCEPTED", workItemId, missionId, runId, digest,
                Map.of("commandId", commandId));
        nodeEvent("started-passed", "RUN_STARTED", workItemId, missionId, runId, digest,
                Map.of("resumable", true));
        nodeEvent("progress-passed", "PROGRESS", workItemId, missionId, runId, digest,
                Map.of("progressSummary", "No-tool synthesis is running"));
        nodeEvent("terminal-passed", "EXECUTION_FINISHED", workItemId, missionId, runId, digest,
                Map.of("runtimeOutcome", "SUCCEEDED", "acceptanceStatus", "PASSED",
                        "resultSummary", "Acceptance evidence was produced"));

        String body = mvc.perform(get("/api/client/v1/work-items/{id}", workItemId)
                        .header("Authorization", bearer(SITES_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.missions[0].status").value("completed"))
                .andExpect(jsonPath("$.missions[0].runs[0].status").value("completed"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("runtimeSession", "absolutePath", "runtime-events", "stderr");
    }

    @Test
    @Order(2)
    void unknownAcceptanceNeverCompletesMission() throws Exception {
        JsonNode created = createWorkItem("create-unknown", "node-a");
        String workItemId = created.get("workItemId").asText();
        String missionId = created.get("missionId").asText();
        String digest = created.get("missionDigest").asText();
        hello("hello-unknown", "node-a", NODE_TOKEN);
        JsonNode assignment = poll("poll-unknown", "node-a", NODE_TOKEN);
        String runId = assignment.get("runId").asText();

        nodeEvent("started-unknown", "RUN_STARTED", workItemId, missionId, runId, digest,
                Map.of("resumable", false));
        nodeEvent("terminal-unknown", "EXECUTION_FINISHED", workItemId, missionId, runId, digest,
                Map.of("runtimeOutcome", "SUCCEEDED", "acceptanceStatus", "UNKNOWN",
                        "resultSummary", "Runtime exited without acceptance evidence"));

        mvc.perform(get("/api/client/v1/work-items/{id}", workItemId)
                        .header("Authorization", bearer(HERMES_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("attention_required"))
                .andExpect(jsonPath("$.missions[0].status").value("uncertain"))
                .andExpect(jsonPath("$.missions[0].runs[0].status").value("uncertain"));
    }

    @Test
    @Order(3)
    void acceptsExplicitRuntimeStartFailureWithoutClaimingTheRunStarted() throws Exception {
        JsonNode created = createWorkItem("create-start-failure", "node-a");
        String workItemId = created.get("workItemId").asText();
        String missionId = created.get("missionId").asText();
        String digest = created.get("missionDigest").asText();
        hello("hello-start-failure", "node-a", NODE_TOKEN);
        JsonNode assignment = poll("poll-start-failure", "node-a", NODE_TOKEN);
        String runId = assignment.get("runId").asText();

        nodeEvent("accepted-start-failure", "RUN_ACCEPTED", workItemId, missionId, runId, digest,
                Map.of("commandId", assignment.get("commandId").asText()));
        nodeEvent("terminal-start-failure", "EXECUTION_FAILED", workItemId, missionId, runId, digest,
                Map.of("runtimeOutcome", "FAILED", "acceptanceStatus", "UNKNOWN",
                        "resultSummary", "Runtime could not be started; details remain on the Node"));

        mvc.perform(get("/api/client/v1/work-items/{id}", workItemId)
                        .header("Authorization", bearer(HERMES_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("attention_required"))
                .andExpect(jsonPath("$.missions[0].status").value("uncertain"))
                .andExpect(jsonPath("$.missions[0].runs[0].status").value("uncertain"));
    }

    @Test
    @Order(4)
    void concurrentPollsReturnOneDurableAssignment() throws Exception {
        JsonNode created = createWorkItem("create-concurrent-poll", "node-a");
        hello("hello-concurrent-poll", "node-a", NODE_TOKEN);

        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() ->
                pollUnchecked("poll-concurrent-a", "node-a", NODE_TOKEN));
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() ->
                pollUnchecked("poll-concurrent-b", "node-a", NODE_TOKEN));
        JsonNode firstAssignment = first.join();
        JsonNode secondAssignment = second.join();

        assertThat(firstAssignment.get("runId").asText())
                .isEqualTo(secondAssignment.get("runId").asText());
        mvc.perform(get("/api/client/v1/work-items/{id}", created.get("workItemId").asText())
                        .header("Authorization", bearer(HERMES_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missions[0].runs.length()").value(1));
    }

    @Test
    @Order(5)
    void enforcesScopesNodeBindingIdempotencyAndStrictPayloads() throws Exception {
        String request = createRequest("node-a");
        String first = mvc.perform(post("/api/client/v1/work-items")
                        .header("Authorization", bearer(HERMES_TOKEN))
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/client/v1/work-items")
                        .header("Authorization", bearer(HERMES_TOKEN))
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));

        mvc.perform(post("/api/client/v1/work-items")
                        .header("Authorization", bearer(SITES_TOKEN))
                        .header("Idempotency-Key", "sites-write")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/client/v1/nodes"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/node/v1/hello")
                        .header("Authorization", bearer(NODE_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(helloRequest("bad-binding", "node-b")))
                .andExpect(status().isForbidden());

        String withSensitiveUnknownField = request.substring(0, request.length() - 1)
                + ",\"runtimeSession\":\"must-not-enter-service\"}";
        mvc.perform(post("/api/client/v1/work-items")
                        .header("Authorization", bearer(HERMES_TOKEN))
                        .header("Idempotency-Key", "unknown-field")
                        .contentType(MediaType.APPLICATION_JSON).content(withSensitiveUnknownField))
                .andExpect(status().isBadRequest());
    }

    private JsonNode createWorkItem(String idempotencyKey, String targetNodeId) throws Exception {
        String response = mvc.perform(post("/api/client/v1/work-items")
                        .header("Authorization", bearer(HERMES_TOKEN))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(createRequest(targetNodeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missionDigest").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String createRequest(String targetNodeId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", "No-tool MVP mission",
                "objective", "Produce a short synthesis without tools",
                "acceptanceSummary", "Return a structured terminal result",
                "authorizedSideEffectsSummary", "No external side effects",
                "targetNodeId", targetNodeId,
                "workspaceRef", "workspace-main",
                "runtimeKind", "ws",
                "executionProfile", "no-tools",
                "priority", 0,
                "dataBoundaryAcknowledged", true));
    }

    private void hello(String messageId, String nodeId, String token) throws Exception {
        mvc.perform(post("/api/node/v1/hello")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(helloRequest(messageId, nodeId)))
                .andExpect(status().isOk());
    }

    private String helloRequest(String messageId, String nodeId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "protocolVersion", "1.0", "messageId", messageId, "nodeId", nodeId,
                "sentAt", Instant.now().toString(), "displayName", "Test node",
                "capabilities", new String[] {"runtime:ws"}));
    }

    private JsonNode poll(String messageId, String nodeId, String token) throws Exception {
        String response = mvc.perform(post("/api/node/v1/poll")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "protocolVersion", "1.0", "messageId", messageId, "nodeId", nodeId,
                                "sentAt", Instant.now().toString(), "acknowledgedCommandIds", new String[0]))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode pollUnchecked(String messageId, String nodeId, String token) {
        try {
            return poll(messageId, nodeId, token);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent poll failed", exception);
        }
    }

    private void nodeEvent(String messageId, String eventType, String workItemId, String missionId,
            String runId, String missionDigest, Map<String, Object> eventFields) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("protocolVersion", "1.0");
        payload.put("messageId", messageId);
        payload.put("nodeId", "node-a");
        payload.put("sentAt", Instant.now().toString());
        payload.put("eventType", eventType);
        payload.put("workItemId", workItemId);
        payload.put("missionId", missionId);
        payload.put("runId", runId);
        payload.put("missionDigest", missionDigest);
        payload.putAll(eventFields);
        mvc.perform(post("/api/node/v1/events")
                        .header("Authorization", bearer(NODE_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
