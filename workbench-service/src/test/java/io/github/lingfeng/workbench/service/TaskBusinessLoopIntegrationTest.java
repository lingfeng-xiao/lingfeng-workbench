package io.github.lingfeng.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskBusinessLoopIntegrationTest {
  private static final String CREATOR = "task-creator-token-000000000000000000000000";
  private static final String SITES = "task-reader-token-0000000000000000000000000";
  private static final String HERMES = "task-hermes-token-0000000000000000000000000";
  private static final String NODE = "task-node-token-00000000000000000000000000";
  private static final Path DATABASE =
      Path.of("target", "task-business-loop-" + UUID.randomUUID() + ".db");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
    registry.add("workbench.security.creator-token", () -> CREATOR);
    registry.add("workbench.security.sites-token", () -> SITES);
    registry.add("workbench.security.hermes-token", () -> HERMES);
    registry.add("workbench.security.node-tokens.node-alpha", () -> NODE);
    registry.add("workbench.task.observation-stale-after", () -> "5m");
  }

  @Test
  void closesCreateStartReviewAcceptArchiveAndRestoreWithoutCollapsingStateAxes()
      throws Exception {
    JsonNode created = create("task-create-1", "P1 business loop");
    String taskId = created.get("taskId").asText();
    assertThat(count("work_items")).isZero();

    JsonNode replay = create("task-create-1", "P1 business loop");
    assertThat(replay.get("taskId").asText()).isEqualTo(taskId);
    assertThat(count("tasks")).isEqualTo(1);

    JsonNode edited =
        response(
            put("/api/tasks/v1/tasks/{taskId}", taskId)
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "task-edit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(1, "Edited P1 business loop")));
    assertThat(edited.get("businessStatus").asText()).isEqualTo("DRAFT");
    assertThat(count("work_items")).isZero();

    JsonNode ready =
        action(
            taskId,
            "mark-ready",
            "task-ready-1",
            edited.get("version").asLong(),
            "Ready for explicit start");
    assertThat(ready.get("businessStatus").asText()).isEqualTo("READY");
    assertThat(count("work_items")).isZero();

    mvc.perform(
            put("/api/tasks/v1/tasks/{taskId}", taskId)
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "task-stale-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(1, "Stale edit")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("currentVersion")));

    String startRequest = actionBody(ready.get("version").asLong(), "User explicitly started Task");
    JsonNode started =
        response(
            post("/api/tasks/v1/tasks/{taskId}/start", taskId)
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "task-start-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(startRequest));
    assertThat(started.get("businessStatus").asText()).isEqualTo("IN_PROGRESS");
    JsonNode startReplay =
        response(
            post("/api/tasks/v1/tasks/{taskId}/start", taskId)
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "task-start-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(startRequest));
    assertThat(startReplay.get("runId").asText()).isEqualTo(started.get("runId").asText());
    assertThat(count("work_items")).isEqualTo(1);
    assertThat(count("runs")).isEqualTo(1);

    hello("task-hello-1");
    String startCommandBody = poll("poll-task-start");
    JsonNode startCommand = mapper.readTree(startCommandBody);
    assertThat(startCommand.get("contextRefs").get(0).asText()).isEqualTo("context-design");
    completeCommand(startCommandBody, "task-first");

    JsonNode review = detail(taskId);
    assertThat(review.get("businessStatus").asText()).isEqualTo("REVIEW");
    assertThat(review.get("acceptanceStatus").asText()).isEqualTo("PENDING");
    assertThat(review.get("businessStatus").asText()).isNotEqualTo("DONE");
    assertThat(review.get("runs").get(0).get("status").asText()).isEqualTo("completed");
    assertThat(review.get("timeline").size()).isGreaterThanOrEqualTo(9);

    JsonNode accepted =
        response(
            post("/api/tasks/v1/tasks/{taskId}/accept", taskId)
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "task-accept-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(review.get("version").asLong())));
    assertThat(accepted.get("businessStatus").asText()).isEqualTo("DONE");
    assertThat(accepted.get("acceptanceStatus").asText()).isEqualTo("ACCEPTED");

    JsonNode archived =
        action(
            taskId,
            "archive",
            "task-archive-1",
            accepted.get("version").asLong(),
            "Remove completed Task from active pool");
    assertThat(archived.get("businessStatus").asText()).isEqualTo("ARCHIVED");
    mvc.perform(
            get("/api/tasks/v1/tasks")
                .header("Authorization", bearer(SITES))
                .queryParam("includeArchived", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].taskId").value(taskId));
    JsonNode restored =
        action(
            taskId,
            "restore",
            "task-restore-1",
            archived.get("version").asLong(),
            "Restore for history review");
    assertThat(restored.get("businessStatus").asText()).isEqualTo("DONE");

    assertThat(Files.readString(DATABASE, StandardCharsets.ISO_8859_1))
        .doesNotContain(
            "C:\\Users\\owner\\workspace",
            "runtimeSessionId",
            "sourceDiff",
            "rawRuntimeEvent",
            "secret-token-value");
  }

  @Test
  void requestChangesCreatesANewRunWithoutOverwritingTheOldRun() throws Exception {
    JsonNode created = create("task-create-retry", "Retry history");
    String taskId = created.get("taskId").asText();
    JsonNode ready = action(taskId, "mark-ready", "retry-ready", 1, "Ready first run");
    JsonNode firstStart = start(taskId, "retry-start-1", ready.get("version").asLong());
    hello("task-hello-retry");
    completeCommand(poll("retry-first-poll"), "retry-first");
    JsonNode review = detail(taskId);
    JsonNode returned =
        action(
            taskId,
            "request-changes",
            "retry-request-changes",
            review.get("version").asLong(),
            "Please adjust the implementation");
    assertThat(returned.get("businessStatus").asText()).isEqualTo("READY");
    assertThat(returned.get("acceptanceStatus").asText()).isEqualTo("CHANGES_REQUESTED");
    JsonNode secondStart = start(taskId, "retry-start-2", returned.get("version").asLong());
    assertThat(secondStart.get("runId").asText()).isNotEqualTo(firstStart.get("runId").asText());
    assertThat(detail(taskId).get("runs").size()).isEqualTo(2);
  }

  @Test
  void requiresRuntimeSuccessAndIndependentAcceptanceBeforeReview() throws Exception {
    JsonNode created = create("task-create-trust", "Trusted terminal gate");
    String taskId = created.get("taskId").asText();
    JsonNode ready = action(taskId, "mark-ready", "trust-ready", 1, "Ready for trusted Run");
    start(taskId, "trust-start-unknown", ready.get("version").asLong());
    hello("task-hello-trust");
    completeCommand(poll("trust-poll-unknown"), "trust-unknown", "SUCCEEDED", "UNKNOWN");

    JsonNode uncertain = detail(taskId);
    assertThat(uncertain.get("businessStatus").asText()).isEqualTo("READY");
    assertThat(uncertain.get("acceptanceStatus").asText()).isNotEqualTo("PENDING");
    assertThat(uncertain.get("attentionState").asText()).isEqualTo("RUN_UNCERTAIN");
    assertThat(uncertain.get("runs").get(0).get("status").asText()).isEqualTo("uncertain");

    start(taskId, "trust-start-failed", uncertain.get("version").asLong());
    completeCommand(poll("trust-poll-failed"), "trust-failed", "SUCCEEDED", "FAILED");

    JsonNode failed = detail(taskId);
    assertThat(failed.get("businessStatus").asText()).isEqualTo("READY");
    assertThat(failed.get("acceptanceStatus").asText()).isNotEqualTo("PENDING");
    assertThat(failed.get("attentionState").asText()).isEqualTo("RUN_FAILED");
    assertThat(failed.get("runs").get(0).get("status").asText()).isEqualTo("failed");
    assertThat(failed.get("runs").size()).isEqualTo(2);
  }

  private JsonNode create(String key, String title) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/api/tasks/v1/tasks")
                    .header("Authorization", bearer(CREATOR))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(title)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode detail(String taskId) throws Exception {
    return mapper.readTree(
        mvc.perform(
                get("/api/tasks/v1/tasks/{taskId}", taskId)
                    .header("Authorization", bearer(SITES)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode action(
      String taskId, String action, String key, long version, String reason) throws Exception {
    return response(
        post("/api/tasks/v1/tasks/{taskId}/{action}", taskId, action)
            .header("Authorization", bearer(CREATOR))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(actionBody(version, reason)));
  }

  private JsonNode start(String taskId, String key, long version) throws Exception {
    return response(
        post("/api/tasks/v1/tasks/{taskId}/start", taskId)
            .header("Authorization", bearer(CREATOR))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(actionBody(version, "Explicit start")));
  }

  private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mapper.readTree(
        mvc.perform(request)
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void completeCommand(String commandBody, String prefix) throws Exception {
    completeCommand(commandBody, prefix, "SUCCEEDED", "PASSED");
  }

  private void completeCommand(
      String commandBody, String prefix, String runtimeOutcome, String acceptanceStatus)
      throws Exception {
    JsonNode command = mapper.readTree(commandBody);
    String workItemId = command.get("workItemId").asText();
    String missionId = command.get("missionId").asText();
    String runId = command.get("runId").asText();
    String digest = command.get("missionDigest").asText();
    event(
        prefix + "-ack",
        1,
        "COMMAND_STORED",
        workItemId,
        missionId,
        runId,
        digest,
        Map.of(
            "commandId", command.get("commandId").asText(),
            "commandPayloadDigest", sha(commandBody)));
    event(
        prefix + "-start",
        2,
        "RUN_STARTED",
        workItemId,
        missionId,
        runId,
        digest,
        Map.of("resumable", true));
    event(
        prefix + "-progress-1",
        3,
        "PROGRESS_UPDATED",
        workItemId,
        missionId,
        runId,
        digest,
        Map.of("progressSummary", "Implementation started"));
    event(
        prefix + "-progress-2",
        4,
        "PROGRESS_UPDATED",
        workItemId,
        missionId,
        runId,
        digest,
        Map.of("progressSummary", "Validation completed"));
    event(
        prefix + "-terminal",
        5,
        "RUN_TERMINAL",
        workItemId,
        missionId,
        runId,
        digest,
        Map.of(
            "runtimeOutcome",
            runtimeOutcome,
            "acceptanceStatus",
            acceptanceStatus,
            "resultSummary",
            "Runtime completed with local evidence",
            "resumable",
            false));
  }

  private String createBody(String title) throws Exception {
    return mapper.writeValueAsString(
        Map.ofEntries(
            Map.entry("title", title),
            Map.entry("objective", "Implement the frozen P1 loop"),
            Map.entry("acceptanceSummary", "All bounded checks pass"),
            Map.entry("sideEffectSummary", "Local code and tests only"),
            Map.entry("priority", 5),
            Map.entry("targetNodeId", "node-alpha"),
            Map.entry("workspaceRef", "workspace-main"),
            Map.entry(
                "contextRefs",
                new Object[] {Map.of("ref", "context-design", "label", "Frozen design")}),
            Map.entry("runtimeKind", "fake-session"),
            Map.entry("executionProfile", "development-v1"),
            Map.entry("dataBoundaryAcknowledged", true),
            Map.entry("actor", "owner"),
            Map.entry("reason", "Create a durable Task")));
  }

  private String updateBody(long version, String title) throws Exception {
    return mapper.writeValueAsString(
        Map.ofEntries(
            Map.entry("expectedVersion", version),
            Map.entry("title", title),
            Map.entry("objective", "Implement and verify the frozen P1 loop"),
            Map.entry("acceptanceSummary", "All bounded checks pass"),
            Map.entry("sideEffectSummary", "Local code and tests only"),
            Map.entry("priority", 6),
            Map.entry("targetNodeId", "node-alpha"),
            Map.entry("workspaceRef", "workspace-main"),
            Map.entry(
                "contextRefs",
                new Object[] {Map.of("ref", "context-design", "label", "Frozen design")}),
            Map.entry("runtimeKind", "fake-session"),
            Map.entry("executionProfile", "development-v1"),
            Map.entry("actor", "owner"),
            Map.entry("reason", "Clarify the objective")));
  }

  private String actionBody(long version, String reason) throws Exception {
    return mapper.writeValueAsString(
        Map.of("expectedVersion", version, "actor", "owner", "reason", reason));
  }

  private String acceptBody(long version) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "expectedVersion",
            version,
            "actor",
            "owner",
            "reason",
            "Delivery reviewed and accepted",
            "deliverySummary",
            "P1 loop completed with durable evidence",
            "commitSha",
            "abcdef1234567890",
            "prUrl",
            "https://example.test/pull/1"));
  }

  private void hello(String messageId) throws Exception {
    mvc.perform(
            post("/api/node/v2/hello")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"protocolVersion\":\"2.0\",\"messageId\":\""
                        + messageId
                        + "\",\"nodeId\":\"node-alpha\",\"sentAt\":\"2026-08-21T01:00:00Z\",\"displayName\":\"Alpha\",\"capabilities\":[\"runtime:fake\"]}"))
        .andExpect(status().isOk());
  }

  private String poll(String messageId) throws Exception {
    return mvc.perform(
            post("/api/node/v2/poll")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"protocolVersion\":\"2.0\",\"messageId\":\""
                        + messageId
                        + "\",\"nodeId\":\"node-alpha\",\"sentAt\":\"2026-08-21T01:00:00Z\",\"maxCommands\":1}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void event(
      String messageId,
      long sequence,
      String eventType,
      String workItemId,
      String missionId,
      String runId,
      String digest,
      Map<String, Object> fields)
      throws Exception {
    LinkedHashMap<String, Object> event = new LinkedHashMap<>();
    event.put("protocolVersion", "2.0");
    event.put("messageId", messageId);
    event.put("nodeId", "node-alpha");
    event.put("sentAt", Instant.parse("2026-08-21T01:00:00Z").plusSeconds(sequence));
    event.put("eventType", eventType);
    event.put("localSequence", sequence);
    event.put("workItemId", workItemId);
    event.put("missionId", missionId);
    event.put("runId", runId);
    event.put("missionDigest", digest);
    event.putAll(fields);
    mvc.perform(
            post("/api/node/v2/events")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(event)))
        .andExpect(status().isOk());
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
