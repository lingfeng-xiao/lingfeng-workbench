package io.github.lingfeng.workbench.node.orchestration;

import static io.github.lingfeng.workbench.node.TestAssignments.assignment;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.connection.NodeProtocolClient;
import io.github.lingfeng.workbench.node.connection.ProtocolClientException;
import io.github.lingfeng.workbench.node.localstate.LocalNodeStore;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import io.github.lingfeng.workbench.node.protocol.PollResponse;
import io.github.lingfeng.workbench.node.protocol.ProtocolAck;
import io.github.lingfeng.workbench.node.runtime.RuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.RuntimeCapabilities;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.RuntimeEventSink;
import io.github.lingfeng.workbench.node.runtime.RuntimeExecutionContext;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.RuntimeResumeContext;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssignmentExecutorTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsPassedTerminalWithoutLeakingRuntimeSessionOrLocalPath() {
        FakeProtocolClient service = new FakeProtocolClient();
        FakeRuntime runtime = new FakeRuntime(List.of(
                new RuntimeEvent.Started(true, "secret-session"),
                new RuntimeEvent.Progress("working"),
                new RuntimeEvent.Finished(
                        RuntimeEvent.RuntimeOutcome.SUCCEEDED,
                        RuntimeEvent.AcceptanceStatus.PASSED,
                        "accepted")));
        AssignmentExecutor executor = executor(service, runtime);

        executor.execute(assignment());

        assertThat(runtime.startCount).isEqualTo(1);
        assertThat(service.events).extracting(OutboundEvent::eventType)
                .containsExactly("RUN_ACCEPTED", "RUN_STARTED", "PROGRESS", "EXECUTION_FINISHED");
        JsonNode terminal = service.events.getLast().payload();
        assertThat(terminal.path("acceptanceStatus").asText()).isEqualTo("PASSED");
        assertThat(service.events.toString())
                .doesNotContain("secret-session")
                .doesNotContain(temporaryDirectory.toAbsolutePath().toString());
    }

    @Test
    void reportsUnknownWhenRuntimeEndsWithoutTerminalAndDoesNotRestartDuplicate() {
        FakeProtocolClient service = new FakeProtocolClient();
        FakeRuntime runtime = new FakeRuntime(List.of(new RuntimeEvent.Started(false, null)));
        AssignmentExecutor executor = executor(service, runtime);

        executor.execute(assignment());
        executor.execute(assignment());

        assertThat(runtime.startCount).isEqualTo(1);
        JsonNode terminal = service.events.getLast().payload();
        assertThat(terminal.path("eventType").asText()).isEqualTo("EXECUTION_FAILED");
        assertThat(terminal.path("acceptanceStatus").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void keepsEventInDurableOutboxUntilFakeServiceAcknowledgesIt() {
        FakeProtocolClient service = new FakeProtocolClient();
        service.failRetryably = true;
        FakeRuntime runtime = new FakeRuntime(List.of(new RuntimeEvent.Finished(
                RuntimeEvent.RuntimeOutcome.SUCCEEDED,
                RuntimeEvent.AcceptanceStatus.PASSED,
                "accepted")));
        LocalNodeStore store = new LocalNodeStore(temporaryDirectory.resolve("state"), objectMapper);
        AssignmentExecutor executor = executor(service, runtime, store);

        executor.execute(assignment());
        assertThat(store.pendingEvents(10)).isNotEmpty();

        service.failRetryably = false;
        executor.flushOutbox();
        assertThat(store.pendingEvents(10)).isEmpty();
    }

    private AssignmentExecutor executor(FakeProtocolClient service, FakeRuntime runtime) {
        return executor(service, runtime, new LocalNodeStore(temporaryDirectory.resolve("state"), objectMapper));
    }

    private AssignmentExecutor executor(
            FakeProtocolClient service,
            FakeRuntime runtime,
            LocalNodeStore store) {
        NodeProperties properties = new NodeProperties(
                "office-pc",
                "Office PC",
                URI.create("https://service.example/"),
                "x".repeat(32),
                temporaryDirectory.resolve("state"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                "ws",
                "ws",
                Map.of("sandbox", temporaryDirectory.resolve("workspace")));
        Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);
        return new AssignmentExecutor(
                properties,
                store,
                service,
                runtime,
                new NodeEventFactory(properties, objectMapper, clock));
    }

    private static final class FakeProtocolClient implements NodeProtocolClient {

        private final List<OutboundEvent> events = new ArrayList<>();
        private boolean failRetryably;

        @Override
        public ProtocolAck hello(Set<String> capabilities) {
            return new ProtocolAck("hello", false);
        }

        @Override
        public ProtocolAck heartbeat(String activeRunId) {
            return new ProtocolAck("heartbeat", false);
        }

        @Override
        public PollResponse poll(List<String> acknowledgedCommandIds) {
            return new PollResponse.NoCommand();
        }

        @Override
        public ProtocolAck sendEvent(OutboundEvent event) {
            if (failRetryably) {
                throw new ProtocolClientException("offline", true);
            }
            events.add(event);
            return new ProtocolAck(event.messageId(), false);
        }
    }

    private static final class FakeRuntime implements RuntimeAdapter {

        private final List<RuntimeEvent> events;
        private int startCount;

        private FakeRuntime(List<RuntimeEvent> events) {
            this.events = events;
        }

        @Override
        public RuntimeProbe probe() {
            return new RuntimeProbe(true, "fake");
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return new RuntimeCapabilities("fake", Set.of("structured-events"));
        }

        @Override
        public void start(RuntimeExecutionContext context, RuntimeEventSink eventSink) {
            startCount++;
            events.forEach(eventSink::emit);
        }

        @Override
        public void resume(RuntimeResumeContext context, RuntimeEventSink eventSink) {
            throw new UnsupportedOperationException("not used by MVP-N1");
        }

        @Override
        public void cancel() {
        }
    }
}
