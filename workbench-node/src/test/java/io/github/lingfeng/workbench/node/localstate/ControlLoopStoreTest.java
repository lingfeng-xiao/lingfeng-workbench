package io.github.lingfeng.workbench.node.localstate;

import static io.github.lingfeng.workbench.node.V2TestCommands.DIGEST;
import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.response;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolValidation;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlLoopStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCommandAndAckEventAtomicallyAndDeduplicatesWithoutSecondEvent() throws Exception {
        ControlLoopStore store = store();

        assertThat(store.storeCommand(start()).status()).isEqualTo(ControlLoopStore.StoreStatus.NEW);
        assertThat(store.pendingEvents(10)).hasSize(1);
        assertThat(store.pendingEvents(10).getFirst().eventType()).isEqualTo("COMMAND_STORED");
        assertThat(store.pendingEvents(10).getFirst().payload().path("localSequence").asLong()).isEqualTo(1);

        assertThat(store.storeCommand(start()).status()).isEqualTo(ControlLoopStore.StoreStatus.DUPLICATE);
        assertThat(store.pendingEvents(10)).hasSize(1);
        assertThat(Files.readAllLines(temporaryDirectory.resolve("runs/run_001/control-commands.ndjson")))
                .hasSize(1);
    }

    @Test
    void rejectsSameCommandIdWithDifferentPayload() {
        ControlLoopStore store = store();
        store.storeCommand(start());
        var changedPayload = start().payload().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedPayload).put("objective", "Different objective");
        NodeCommand changed = ProtocolValidation.parseCommand(changedPayload, "node_alpha");

        assertThatThrownBy(() -> store.storeCommand(changed))
                .isInstanceOf(LocalStateException.class).hasMessageContaining("different durable payload");
    }

    @Test
    void interactionBindingFailsClosedAndValidResponseIsStoredOnce() {
        ControlLoopStore store = store();
        store.storeCommand(start());
        assertThatThrownBy(() -> store.storeCommand(response()))
                .isInstanceOf(LocalStateException.class).hasMessageContaining("not waiting");
        store.saveSession("run_001", new SessionHandle(
                "local-secret-session", "fake-session", "test", temporaryDirectory.toString()), true);
        store.recordInteraction("run_001", "int_001", "cp_001", "Approve?", Set.of("APPROVE"), true);

        assertThatThrownBy(() -> store.storeCommand(response("cp_wrong", DIGEST, "node_alpha", "cmd_wrong")))
                .isInstanceOf(LocalStateException.class).hasMessageContaining("binding");
        assertThat(store.storeCommand(response()).status()).isEqualTo(ControlLoopStore.StoreStatus.NEW);
        assertThat(store.recordInteractionConsumed(response())).isTrue();
        assertThat(store.recordInteractionConsumed(response())).isFalse();
        assertThat(store.storeCommand(response()).status()).isEqualTo(ControlLoopStore.StoreStatus.DUPLICATE);
        assertThat(store.pendingEvents(20)).extracting(event -> event.eventType())
                .containsExactly("COMMAND_STORED", "INTERACTION_REQUESTED", "COMMAND_STORED",
                        "INTERACTION_RESPONSE_CONSUMED");
        assertThat(store.pendingEvents(20).toString()).doesNotContain("local-secret-session");
    }

    @Test
    void wrongMissionDigestBindingFailsClosed() {
        ControlLoopStore store = store();
        store.storeCommand(start());
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)
                io.github.lingfeng.workbench.node.V2TestCommands.cancel().payload().deepCopy();
        payload.put("missionDigest", "b".repeat(64));

        assertThatThrownBy(() -> store.storeCommand(ProtocolValidation.parseCommand(payload, "node_alpha")))
                .isInstanceOf(LocalStateException.class).hasMessageContaining("binding");
    }

    @Test
    void firstDurableTerminalWinsAndOutboxSurvivesReopen() {
        ControlLoopStore first = store();
        first.storeCommand(start());
        assertThat(first.tryRecordTerminal(
                "run_001", NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                NormalizedRuntimeEvent.AcceptanceStatus.PASSED, "passed")).isTrue();
        assertThat(first.tryRecordTerminal(
                "run_001", NormalizedRuntimeEvent.RuntimeOutcome.INTERRUPTED,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, "late cancel")).isFalse();

        ControlLoopStore reopened = store();
        assertThat(reopened.pendingEvents(10)).extracting(event -> event.eventType())
                .containsExactly("COMMAND_STORED", "RUN_TERMINAL");
        assertThat(reopened.activeRun()).isEmpty();
    }

    @Test
    void successfulRuntimeWithFailedAcceptanceIsDurablyFailed() throws Exception {
        ControlLoopStore store = store();
        store.storeCommand(start());

        assertThat(store.tryRecordTerminal(
                "run_001", NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                NormalizedRuntimeEvent.AcceptanceStatus.FAILED, "verification failed")).isTrue();

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("node.db"));
             var query = connection.prepareStatement(
                     "SELECT state FROM control_local_run WHERE run_id='run_001'");
             var row = query.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("failed");
        }
    }

    private ControlLoopStore store() {
        return new ControlLoopStore(temporaryDirectory, "node_alpha", MAPPER, Clock.systemUTC());
    }
}
