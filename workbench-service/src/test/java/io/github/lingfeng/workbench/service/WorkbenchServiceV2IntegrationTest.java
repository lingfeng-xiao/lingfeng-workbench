package io.github.lingfeng.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.application.ClientV2ApplicationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchServiceV2IntegrationTest {
  private static final String HERMES = "hermes-v2-token-00000000000000000000000000";
  private static final String CREATOR = "creator-v2-token-0000000000000000000000000";
  private static final String SITES = "sites-v2-token-000000000000000000000000000";
  private static final String NODE = "node-v2-token-0000000000000000000000000000";
  private static final Path DATABASE =
      Path.of("target", "integration-v2-" + UUID.randomUUID() + ".db");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired ClientV2ApplicationService clientService;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
    r.add("workbench.security.hermes-token", () -> HERMES);
    r.add("workbench.security.sites-token", () -> SITES);
    r.add("workbench.security.creator-token", () -> CREATOR);
    r.add("workbench.security.node-tokens.node-alpha", () -> NODE);
    r.add("workbench.notification.lease-duration", () -> "50ms");
    r.add("workbench.notification.max-attempts", () -> 2);
  }

  @Test
  @Order(1)
  void completesDurableInteractionAndNotificationLoopWithIdempotentFailureChecks()
      throws Exception {
    JsonNode created = create("create-v2");
    String work = created.get("workItemId").asText(), mission = created.get("missionId").asText();
    String run = created.get("runId").asText(), digest = created.get("missionDigest").asText();
    mvc.perform(
            post("/api/client/v2/work-items")
                .header("Authorization", bearer(SITES))
                .header("Idempotency-Key", "forbidden")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
        .andExpect(status().isForbidden());
    hello("hello_1");
    String startBody = poll("poll-start");
    JsonNode start = mapper.readTree(startBody);
    assertThat(start.get("commandType").asText()).isEqualTo("START_RUN");
    assertThat(poll("poll-start-retry")).isEqualTo(startBody);
    String startCommand = start.get("commandId").asText();
    event(
        "ack-bad",
        1,
        "COMMAND_STORED",
        work,
        mission,
        run,
        digest,
        Map.of("commandId", startCommand, "commandPayloadDigest", "b".repeat(64)),
        409);
    event(
        "ack-start",
        1,
        "COMMAND_STORED",
        work,
        mission,
        run,
        digest,
        Map.of("commandId", startCommand, "commandPayloadDigest", sha(startBody)),
        200);
    event(
        "start-run", 2, "RUN_STARTED", work, mission, run, digest, Map.of("resumable", true), 200);
    event(
        "phase",
        3,
        "PHASE_CHANGED",
        work,
        mission,
        run,
        digest,
        Map.of("phaseCode", "IMPLEMENTATION", "phaseSummary", "Implementing frozen contract"),
        200);
    event(
        "interaction",
        4,
        "INTERACTION_REQUESTED",
        work,
        mission,
        run,
        digest,
        Map.of(
            "interactionId",
            "int_001",
            "checkpointId",
            "cp_001",
            "targetNodeId",
            "node-alpha",
            "promptSummary",
            "Approve local validation?",
            "allowedDecisions",
            new String[] {"APPROVE", "REJECT"},
            "resumable",
            true),
        200);
    event(
        "interaction",
        4,
        "INTERACTION_REQUESTED",
        work,
        mission,
        run,
        digest,
        Map.of(
            "interactionId",
            "int_001",
            "checkpointId",
            "cp_001",
            "targetNodeId",
            "node-alpha",
            "promptSummary",
            "Approve local validation?",
            "allowedDecisions",
            new String[] {"APPROVE", "REJECT"},
            "resumable",
            true),
        200);

    JsonNode notification =
        mapper.readTree(
            mvc.perform(
                    post("/api/client/v2/notifications/poll")
                        .header("Authorization", bearer(HERMES))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"requestId\":\"np_1\",\"targetAlias\":\"owner\",\"sentAt\":\"2026-08-20T01:06:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationType").value("INTERACTION_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    String notificationId = notification.get("notificationId").asText();
    String resolution =
        "{\"interactionId\":\"int_001\",\"runId\":\""
            + run
            + "\",\"checkpointId\":\"cp_001\",\"missionDigest\":\""
            + digest
            + "\",\"decision\":\"APPROVE\",\"responseSummary\":\"Approved frozen"
            + " scope\",\"resolvedBy\":\"hermes-owner\",\"resolvedAt\":\"2026-08-20T01:09:00Z\"}";
    mvc.perform(
            post("/api/client/v2/interactions/int_001/resolution")
                .header("Authorization", bearer(HERMES))
                .header("Idempotency-Key", "resolve-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(resolution.replace("cp_001", "cp_wrong")))
        .andExpect(status().isConflict());
    JsonNode resolved =
        mapper.readTree(
            mvc.perform(
                    post("/api/client/v2/interactions/int_001/resolution")
                        .header("Authorization", bearer(HERMES))
                        .header("Idempotency-Key", "resolve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolution))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(
            post("/api/client/v2/interactions/int_001/resolution")
                .header("Authorization", bearer(HERMES))
                .header("Idempotency-Key", "resolve-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(resolution))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));
    String responseBody = poll("poll-response");
    JsonNode response = mapper.readTree(responseBody);
    assertThat(response.get("commandId").asText()).isEqualTo(resolved.get("commandId").asText());
    event(
        "ack-response",
        5,
        "COMMAND_STORED",
        work,
        mission,
        run,
        digest,
        Map.of(
            "commandId",
            response.get("commandId").asText(),
            "commandPayloadDigest",
            sha(responseBody)),
        200);
    event(
        "consumed",
        6,
        "INTERACTION_RESPONSE_CONSUMED",
        work,
        mission,
        run,
        digest,
        Map.of(
            "interactionId",
            "int_001",
            "checkpointId",
            "cp_001",
            "targetNodeId",
            "node-alpha",
            "responseCommandId",
            response.get("commandId").asText()),
        200);
    event(
        "terminal",
        7,
        "RUN_TERMINAL",
        work,
        mission,
        run,
        digest,
        Map.of(
            "runtimeOutcome",
            "SUCCEEDED",
            "acceptanceStatus",
            "PASSED",
            "resultSummary",
            "All frozen checks passed",
            "resumable",
            false),
        200);
    event(
        "late",
        8,
        "RUN_TERMINAL",
        work,
        mission,
        run,
        digest,
        Map.of(
            "runtimeOutcome",
            "FAILED",
            "acceptanceStatus",
            "FAILED",
            "resultSummary",
            "Late failure",
            "resumable",
            false),
        409);
    mvc.perform(get("/api/client/v2/work-items/{id}", work).header("Authorization", bearer(SITES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.run.status").value("completed"))
        .andExpect(jsonPath("$.run.phaseCode").value("IMPLEMENTATION"))
        .andExpect(jsonPath("$.interactions[0].state").value("consumed"));
    String delivery =
        "{\"deliveryEventId\":\"delivery_1\",\"notificationId\":\""
            + notificationId
            + "\",\"outcome\":\"DELIVERED\",\"reportedAt\":\"2026-08-20T01:10:00Z\"}";
    mvc.perform(
            post("/api/client/v2/notifications/{id}/delivery-events", notificationId)
                .header("Authorization", bearer(HERMES))
                .header("Idempotency-Key", "delivery-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(delivery))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("delivered"))
        .andExpect(jsonPath("$.duplicate").value(false));
    mvc.perform(
            post("/api/client/v2/notifications/{id}/delivery-events", notificationId)
                .header("Authorization", bearer(HERMES))
                .header("Idempotency-Key", "delivery-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(delivery))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));
    mvc.perform(
            post("/api/client/v2/notifications/{id}/delivery-events", notificationId)
                .header("Authorization", bearer(HERMES))
                .header("Idempotency-Key", "delivery-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(delivery))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));
    assertThat(Files.readString(DATABASE, StandardCharsets.ISO_8859_1))
        .doesNotContain(
            "runtimeSessionId", "resumeToken", "C:\\\\Users", "sourceDiff", "rawRuntimeEvent");
  }

  @Test
  @Order(2)
  void passedTerminalWinsCancellationRaceAndLateCancelAckIsHarmless() throws Exception {
    JsonNode created = create("create-cancel-race");
    String workItemId = created.get("workItemId").asText();
    String missionId = created.get("missionId").asText();
    String runId = created.get("runId").asText();
    String missionDigest = created.get("missionDigest").asText();
    hello("hello_cancel");
    String startBody = poll("poll-cancel-start");
    String startCommandId = mapper.readTree(startBody).get("commandId").asText();
    event(
        "ack-cancel-start",
        1,
        "COMMAND_STORED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("commandId", startCommandId, "commandPayloadDigest", sha(startBody)),
        200);
    event(
        "start-cancel-run",
        2,
        "RUN_STARTED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("resumable", true),
        200);
    String cancelCommandId = clientService.requestCancellation(runId, "Authorized cancellation");
    String cancelBody = poll("poll-cancel-command");
    assertThat(mapper.readTree(cancelBody).get("commandType").asText()).isEqualTo("CANCEL_RUN");
    event(
        "terminal-before-cancel-ack",
        3,
        "RUN_TERMINAL",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of(
            "runtimeOutcome",
            "SUCCEEDED",
            "acceptanceStatus",
            "PASSED",
            "resultSummary",
            "Passed before cancellation was applied",
            "resumable",
            false),
        200);
    event(
        "late-cancel-ack",
        4,
        "COMMAND_STORED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("commandId", cancelCommandId, "commandPayloadDigest", sha(cancelBody)),
        200);
    mvc.perform(
            get("/api/client/v2/work-items/{id}", workItemId)
                .header("Authorization", bearer(SITES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run.status").value("completed"));
  }

  @Test
  @Order(3)
  void unknownTerminalFailsClosedAndWrongNodeDigestAndStateAreRejected() throws Exception {
    JsonNode created = create("create-unknown-v2");
    String workItemId = created.get("workItemId").asText();
    String missionId = created.get("missionId").asText();
    String runId = created.get("runId").asText();
    String missionDigest = created.get("missionDigest").asText();
    hello("hello_unknown");
    String commandBody = poll("poll-unknown-start");
    String commandId = mapper.readTree(commandBody).get("commandId").asText();
    event(
        "progress-before-start",
        1,
        "PROGRESS_UPDATED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("progressSummary", "Invalid early progress"),
        409);
    event(
        "wrong-digest-binding",
        1,
        "COMMAND_STORED",
        workItemId,
        missionId,
        runId,
        "a".repeat(64),
        Map.of("commandId", commandId, "commandPayloadDigest", sha(commandBody)),
        409);
    mvc.perform(
            post("/api/node/v2/events")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"protocolVersion\":\"2.0\",\"messageId\":\"wrong_node\",\"nodeId\":\"node-beta\",\"sentAt\":\"2026-08-20T01:00:00Z\",\"eventType\":\"RUN_STARTED\",\"localSequence\":1,\"workItemId\":\""
                        + workItemId
                        + "\",\"missionId\":\""
                        + missionId
                        + "\",\"runId\":\""
                        + runId
                        + "\",\"missionDigest\":\""
                        + missionDigest
                        + "\",\"resumable\":true}"))
        .andExpect(status().isForbidden());
    event(
        "ack-unknown-start",
        1,
        "COMMAND_STORED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("commandId", commandId, "commandPayloadDigest", sha(commandBody)),
        200);
    event(
        "start-unknown",
        2,
        "RUN_STARTED",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of("resumable", false),
        200);
    event(
        "terminal-unknown-v2",
        3,
        "RUN_TERMINAL",
        workItemId,
        missionId,
        runId,
        missionDigest,
        Map.of(
            "runtimeOutcome",
            "UNKNOWN",
            "acceptanceStatus",
            "UNKNOWN",
            "resultSummary",
            "Runtime identity is uncertain",
            "resumable",
            false),
        200);
    mvc.perform(
            get("/api/client/v2/work-items/{id}", workItemId)
                .header("Authorization", bearer(SITES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("attention_required"))
        .andExpect(jsonPath("$.run.status").value("uncertain"));
  }

  @Test
  @Order(4)
  void rejectsUnknownFieldsAndPayloadOver64KiB() throws Exception {
    mvc.perform(
            post("/api/client/v2/work-items")
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "unknown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace("}", ",\"runtimeSessionId\":\"forbidden\"}")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/client/v2/work-items")
                .header("Authorization", bearer(CREATOR))
                .header("Idempotency-Key", "large")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(65537)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  @Order(5)
  void appliesForwardMigrationWithForeignKeysAndSurvivesRepeatedQueries() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class))
        .isEqualTo(3);
    assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM control_commands", Integer.class))
        .isGreaterThanOrEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class))
        .isEqualTo(3);
  }

  private JsonNode create(String key) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/api/client/v2/work-items")
                    .header("Authorization", bearer(CREATOR))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.missionRevision").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String createBody() throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "title",
            "S2 flow",
            "objective",
            "Implement the frozen control loop",
            "acceptanceSummary",
            "All local gates pass",
            "authorizedSideEffectsSummary",
            "Local code and tests only",
            "targetNodeId",
            "node-alpha",
            "workspaceRef",
            "workspace-main",
            "runtimeKind",
            "fake-session",
            "executionProfile",
            "spm-change-v1",
            "priority",
            0,
            "dataBoundaryAcknowledged",
            true));
  }

  private void hello(String messageId) throws Exception {
    mvc.perform(
            post("/api/node/v2/hello")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"protocolVersion\":\"2.0\",\"messageId\":\""
                        + messageId
                        + "\",\"nodeId\":\"node-alpha\",\"sentAt\":\"2026-08-20T01:00:00Z\",\"displayName\":\"Alpha\",\"capabilities\":[\"runtime:fake\"]}"))
        .andExpect(status().isOk());
  }

  private String poll(String id) throws Exception {
    return mvc.perform(
            post("/api/node/v2/poll")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"protocolVersion\":\"2.0\",\"messageId\":\""
                        + id
                        + "\",\"nodeId\":\"node-alpha\",\"sentAt\":\"2026-08-20T01:00:00Z\",\"maxCommands\":1}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void event(
      String id,
      long sequence,
      String type,
      String work,
      String mission,
      String run,
      String digest,
      Map<String, Object> extra,
      int expected)
      throws Exception {
    LinkedHashMap<String, Object> p = new LinkedHashMap<>();
    p.put("protocolVersion", "2.0");
    p.put("messageId", id);
    p.put("nodeId", "node-alpha");
    p.put("sentAt", Instant.parse("2026-08-20T01:00:00Z").plusSeconds(sequence));
    p.put("eventType", type);
    p.put("localSequence", sequence);
    p.put("workItemId", work);
    p.put("missionId", mission);
    p.put("runId", run);
    p.put("missionDigest", digest);
    p.putAll(extra);
    mvc.perform(
            post("/api/node/v2/events")
                .header("Authorization", bearer(NODE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(p)))
        .andExpect(status().is(expected));
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
