package io.github.lingfeng.workbench.node.localstate;

import static io.github.lingfeng.workbench.node.TestAssignments.assignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalNodeStoreTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializesRequiredEvidenceFilesAndPersistsOutboxAcrossRestart() throws Exception {
        LocalNodeStore firstStore = new LocalNodeStore(temporaryDirectory, objectMapper);

        RunRegistration registration = firstStore.registerAssignment(assignment());
        ObjectNode payload = objectMapper.createObjectNode()
                .put("messageId", "message-1")
                .put("eventType", "RUN_ACCEPTED");
        firstStore.enqueueEvent(
                new OutboundEvent("message-1", "run-1", "RUN_ACCEPTED", payload),
                "ACCEPTED",
                false,
                null);

        assertThat(registration.status()).isEqualTo(RunRegistration.Status.NEW);
        assertThat(registration.evidenceDirectory()).isDirectory();
        assertThat(registration.evidenceDirectory().resolve("mission.json")).isRegularFile();
        assertThat(registration.evidenceDirectory().resolve("normalized-events.ndjson")).isRegularFile();
        assertThat(registration.evidenceDirectory().resolve("runtime-events.ndjson")).isRegularFile();
        assertThat(registration.evidenceDirectory().resolve("runtime-stderr.log")).isRegularFile();
        assertThat(registration.evidenceDirectory().resolve("result.md")).isRegularFile();

        LocalNodeStore restartedStore = new LocalNodeStore(temporaryDirectory, objectMapper);
        assertThat(restartedStore.pendingEvents(10)).hasSize(1);
        restartedStore.acknowledgeEvent("message-1");
        assertThat(restartedStore.pendingEvents(10)).isEmpty();
        assertThat(Files.readString(registration.evidenceDirectory().resolve("mission.json")))
                .contains("missionDigest")
                .doesNotContain("runtimeSession");
    }

    @Test
    void treatsExactAssignmentAsDuplicateAndRejectsConflictingDigest() {
        LocalNodeStore store = new LocalNodeStore(temporaryDirectory, objectMapper);
        store.registerAssignment(assignment());

        assertThat(store.registerAssignment(assignment()).status())
                .isEqualTo(RunRegistration.Status.EXISTING_ACTIVE);

        Assignment conflict = new Assignment(
                assignment().commandId(),
                assignment().workItemId(),
                assignment().missionId(),
                assignment().runId(),
                assignment().missionRevision(),
                "b".repeat(64),
                assignment().objective(),
                assignment().acceptanceSummary(),
                assignment().authorizedSideEffectsSummary(),
                assignment().targetNodeId(),
                assignment().workspaceRef(),
                assignment().runtimeKind(),
                assignment().executionProfile());

        assertThatThrownBy(() -> store.registerAssignment(conflict))
                .isInstanceOf(LocalStateException.class)
                .hasMessageContaining("conflicts");
    }
}
