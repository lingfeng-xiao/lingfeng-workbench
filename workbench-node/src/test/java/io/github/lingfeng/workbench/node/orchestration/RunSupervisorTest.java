package io.github.lingfeng.workbench.node.orchestration;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.cancel;
import static io.github.lingfeng.workbench.node.V2TestCommands.response;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.runtime.fake.FakeSessionRuntimeAdapter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSupervisorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void fakeRuntimeCompletesThreeTurnsWithTrustedTerminal() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(20), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(properties("FLOW"), store, runtime)) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());

            await(() -> terminalEvents(store).size() == 1);

            List<DurableNodeEvent> events = store.pendingEvents(50);
            assertThat(events).extracting(DurableNodeEvent::eventType)
                    .contains("RUN_STARTED", "PHASE_CHANGED", "PROGRESS_UPDATED", "RUN_TERMINAL");
            assertThat(events.stream().filter(event -> event.eventType().equals("PROGRESS_UPDATED")))
                    .hasSize(3);
            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("SUCCEEDED");
            assertThat(terminalEvents(store).getFirst().payload().path("acceptanceStatus").asText())
                    .isEqualTo("PASSED");
            assertThat(temporaryDirectory.resolve("runs/run_001/result.md"))
                    .hasContent("# Fake Runtime result\n\nThree deterministic Turns completed with SUCCEEDED/PASSED.\n");
        }
    }

    @Test
    void nodeRestartResumesSameFakeSessionAndConsumesInteractionExactlyOnce() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter firstRuntime = new FakeSessionRuntimeAdapter(
                "INTERACTION", Duration.ofMillis(20), MAPPER);
        RunSupervisor firstSupervisor = new RunSupervisor(properties("INTERACTION"), store, firstRuntime);
        store.storeCommand(start());
        firstSupervisor.acceptStoredCommand(start());
        await(() -> hasEvent(store, "INTERACTION_REQUESTED"));
        assertThat(temporaryDirectory.resolve("runs/run_001/checkpoints/cp_001.json")).exists();
        firstSupervisor.close();
        firstRuntime.close();

        FakeSessionRuntimeAdapter recoveredRuntime = new FakeSessionRuntimeAdapter(
                "INTERACTION", Duration.ofMillis(20), MAPPER);
        try (recoveredRuntime;
                RunSupervisor recoveredSupervisor = new RunSupervisor(
                        properties("INTERACTION"), store, recoveredRuntime)) {
            recoveredSupervisor.recover();
            store.storeCommand(response());
            recoveredSupervisor.acceptStoredCommand(response());

            await(() -> terminalEvents(store).size() == 1);

            assertThat(store.pendingEvents(100).stream()
                    .filter(event -> event.eventType().equals("INTERACTION_RESPONSE_CONSUMED")))
                    .hasSize(1);
            assertThat(terminalEvents(store).getFirst().payload().path("acceptanceStatus").asText())
                    .isEqualTo("PASSED");
            assertThat(store.pendingEvents(100).toString())
                    .doesNotContain("fake-session:run_001")
                    .doesNotContain(temporaryDirectory.toAbsolutePath().toString());
        }
    }

    @Test
    void cancelWinsBeforeRuntimeTerminalAndLateRuntimeEventsCannotOverwriteIt() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofSeconds(1), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(properties("FLOW"), store, runtime)) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> hasEvent(store, "RUN_STARTED"));
            store.storeCommand(cancel());
            supervisor.acceptStoredCommand(cancel());

            await(() -> terminalEvents(store).size() == 1);

            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("INTERRUPTED");
            sleep(Duration.ofMillis(1100));
            assertThat(terminalEvents(store)).hasSize(1);
        }
    }

    @Test
    void trustedTerminalWinsBeforeLateCancel() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(10), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(properties("FLOW"), store, runtime)) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> terminalEvents(store).size() == 1);
            store.storeCommand(cancel());
            supervisor.acceptStoredCommand(cancel());
            sleep(Duration.ofMillis(100));

            assertThat(terminalEvents(store)).hasSize(1);
            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void wrongRuntimeDigestFailsClosedAsUncertain() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter(
                "FLOW", Duration.ofMillis(10), MAPPER, "b".repeat(64));
        try (runtime; RunSupervisor supervisor = new RunSupervisor(properties("FLOW"), store, runtime)) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> terminalEvents(store).size() == 1);

            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("UNKNOWN");
            assertThat(terminalEvents(store).getFirst().payload().path("acceptanceStatus").asText())
                    .isEqualTo("UNKNOWN");
        }
    }

    @Test
    void crashBeforeOrAfterCommandAckRecoversWithoutOpeningSecondSession() {
        for (boolean commandAckedBeforeCrash : List.of(false, true)) {
            Path stateDirectory = temporaryDirectory.resolve("crash-" + commandAckedBeforeCrash);
            ControlLoopStore beforeCrash = new ControlLoopStore(
                    stateDirectory, "node_alpha", MAPPER, Clock.systemUTC());
            beforeCrash.storeCommand(start());
            if (commandAckedBeforeCrash) {
                beforeCrash.acknowledgeEvent(beforeCrash.pendingEvents(10).getFirst().messageId());
            }
            ControlLoopStore recovered = new ControlLoopStore(
                    stateDirectory, "node_alpha", MAPPER, Clock.systemUTC());
            FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(10), MAPPER);
            NodeProperties recoveredProperties = properties("FLOW", stateDirectory);
            try (runtime; RunSupervisor supervisor = new RunSupervisor(recoveredProperties, recovered, runtime)) {
                supervisor.recover();
                await(() -> terminalEvents(recovered).size() == 1);
                assertThat(recovered.pendingEvents(100).stream()
                        .filter(event -> event.eventType().equals("RUN_STARTED"))).hasSize(1);
            }
        }
    }

    @Test
    void lostRuntimeHandleFailsClosedAsUncertainWithoutReplacementSession() {
        ControlLoopStore store = store();
        store.storeCommand(start());
        store.markOpeningSession("run_001");
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(10), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(properties("FLOW"), store, runtime)) {
            supervisor.recover();
            await(() -> terminalEvents(store).size() == 1);
            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("UNKNOWN");
            assertThat(store.pendingEvents(100)).extracting(DurableNodeEvent::eventType)
                    .doesNotContain("RUN_STARTED");
        }
    }

    private ControlLoopStore store() {
        return new ControlLoopStore(temporaryDirectory, "node_alpha", MAPPER, Clock.systemUTC());
    }

    private NodeProperties properties(String scenario) {
        return properties(scenario, temporaryDirectory);
    }

    private NodeProperties properties(String scenario, Path stateDirectory) {
        return new NodeProperties(
                "node_alpha", "Node Alpha", URI.create("https://service.example/"), "x".repeat(32),
                stateDirectory, Duration.ofMillis(20), Duration.ofSeconds(2), "fake-session", "ws",
                Map.of("workspace_main", temporaryDirectory.resolve("workspace")), Duration.ofMillis(50),
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(1), null, null, null, null,
                scenario, Duration.ofMillis(20));
    }

    private static boolean hasEvent(ControlLoopStore store, String eventType) {
        return store.pendingEvents(100).stream().anyMatch(event -> event.eventType().equals(eventType));
    }

    private static List<DurableNodeEvent> terminalEvents(ControlLoopStore store) {
        return store.pendingEvents(100).stream()
                .filter(event -> event.eventType().equals("RUN_TERMINAL"))
                .toList();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(10));
        }
        assertThat(condition.getAsBoolean()).as("condition before timeout").isTrue();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
