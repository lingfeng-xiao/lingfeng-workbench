package io.github.lingfeng.workbench.node.orchestration;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.cancel;
import static io.github.lingfeng.workbench.node.V2TestCommands.response;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static io.github.lingfeng.workbench.node.V2TestCommands.startPayload;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolValidation;
import io.github.lingfeng.workbench.node.runtime.fake.FakeSessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSupervisorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void oneMissionPromptCompletesOnlyAfterIndependentAcceptancePasses() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(20), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("FLOW"), store, runtime, passedAcceptance())) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());

            await(() -> terminalEvents(store).size() == 1);

            List<DurableNodeEvent> events = store.pendingEvents(50);
            assertThat(events).extracting(DurableNodeEvent::eventType)
                    .contains("RUN_STARTED", "RUN_TERMINAL");
            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("SUCCEEDED");
            assertThat(terminalEvents(store).getFirst().payload().path("acceptanceStatus").asText())
                    .isEqualTo("PASSED");
        }
    }

    @Test
    void completedRunReleasesTheNodeForASecondDurableRun() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter(
                "FLOW", Duration.ofMillis(20), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("FLOW"), store, runtime, passedAcceptance())) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> terminalEvents(store).size() == 1);

            NodeCommand.StartRun second = secondStart();
            store.storeCommand(second);
            supervisor.acceptStoredCommand(second);
            await(() -> terminalEvents(store).size() == 2);

            assertThat(store.pendingEvents(100).stream()
                    .filter(event -> event.eventType().equals("RUN_STARTED")))
                    .hasSize(2);
            assertThat(terminalEvents(store)).extracting(DurableNodeEvent::runId)
                    .containsExactly("run_001", "run_002");
        }
    }

    @Test
    void runtimeIdleWithoutAcceptanceEvidenceIsUncertain() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(10), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("FLOW"), store, runtime, new FailClosedAcceptanceEvaluator())) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> terminalEvents(store).size() == 1);

            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("SUCCEEDED");
            assertThat(terminalEvents(store).getFirst().payload().path("acceptanceStatus").asText())
                    .isEqualTo("UNKNOWN");
            assertThat(store.activeRunState()).isNull();
        }
    }

    @Test
    void nativeInteractionResponseIsConsumedExactlyOnce() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter(
                "INTERACTION", Duration.ofMillis(20), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("INTERACTION"), store, runtime, passedAcceptance())) {
            store.storeCommand(start());
            supervisor.acceptStoredCommand(start());
            await(() -> hasEvent(store, "INTERACTION_REQUESTED"));

            store.storeCommand(response());
            supervisor.acceptStoredCommand(response());
            await(() -> terminalEvents(store).size() == 1);

            assertThat(store.pendingEvents(100).stream()
                    .filter(event -> event.eventType().equals("INTERACTION_RESPONSE_CONSUMED")))
                    .hasSize(1);
        }
    }

    @Test
    void successfulNativeAbortWinsBeforeLateIdle() {
        ControlLoopStore store = store();
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofSeconds(1), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("FLOW"), store, runtime, passedAcceptance())) {
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
    void lostRuntimeHandleFailsClosedWithoutReplacementSession() {
        ControlLoopStore store = store();
        store.storeCommand(start());
        store.markOpeningSession("run_001");
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(10), MAPPER);
        try (runtime; RunSupervisor supervisor = new RunSupervisor(
                properties("FLOW"), store, runtime, passedAcceptance())) {
            supervisor.recover();
            await(() -> terminalEvents(store).size() == 1);
            assertThat(terminalEvents(store).getFirst().payload().path("runtimeOutcome").asText())
                    .isEqualTo("UNKNOWN");
            assertThat(store.pendingEvents(100)).extracting(DurableNodeEvent::eventType)
                    .doesNotContain("RUN_STARTED");
        }
    }

    private AcceptanceEvaluator passedAcceptance() {
        return (context, session, summary) -> CompletableFuture.completedFuture(
                new AcceptanceResult(NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                        "Independent acceptance checks passed"));
    }

    private NodeCommand.StartRun secondStart() {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) startPayload().deepCopy();
        payload.put("messageId", "msg_cmd_start_2");
        payload.put("commandId", "cmd_start_2");
        payload.put("workItemId", "wi_002");
        payload.put("missionId", "mi_002");
        payload.put("runId", "run_002");
        payload.put("missionRevision", 2);
        return (NodeCommand.StartRun) ProtocolValidation.parseCommand(payload, "node_alpha");
    }

    private ControlLoopStore store() {
        return new ControlLoopStore(temporaryDirectory, "node_alpha", MAPPER, Clock.systemUTC());
    }

    private NodeProperties properties(String scenario) {
        Path workspace;
        try {
            workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Test workspace could not be created", exception);
        }
        return new NodeProperties(
                "node_alpha", "Node Alpha", URI.create("https://service.example/"), "x".repeat(32),
                temporaryDirectory, Duration.ofMillis(20), Duration.ofSeconds(2), "fake-session", null,
                "0.0.0--test", null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(1),
                Map.of("workspace_main", workspace), Duration.ofMillis(50),
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
