package io.github.lingfeng.workbench.node.connection;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.orchestration.RunSupervisor;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolAck;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.PollResult;
import io.github.lingfeng.workbench.node.runtime.fake.FakeSessionRuntimeAdapter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceConnectionLoopTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void networkLoopKeepsPollingWhileRuntimeRunsAndOfflineEventsRemainInOutbox() {
        NodeProperties properties = properties(null, null, null);
        ControlLoopStore store = new ControlLoopStore(
                temporaryDirectory, "node_alpha", MAPPER, Clock.systemUTC());
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(200), MAPPER);
        RunSupervisor supervisor = new RunSupervisor(properties, store, runtime);
        FakeService service = new FakeService();
        service.pollResults.add(new PollResult.Command(start()));
        service.pollResults.add(new PollResult.NoCommand());
        try (runtime; supervisor;
                ServiceConnectionLoop loop = new ServiceConnectionLoop(
                        properties, store, service, runtime, supervisor)) {
            long startedAt = System.nanoTime();
            loop.runCycle();
            loop.runCycle();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(elapsedMillis).isLessThan(500);
            assertThat(service.pollCount).isEqualTo(2);
            service.offline = true;
            sleep(Duration.ofMillis(750));
            assertThatThrownBy(loop::runCycle).isInstanceOf(ProtocolClientException.class);
            assertThat(store.pendingEvents(100)).isNotEmpty();
        }
    }

    @Test
    void duplicateServiceAckDeletesOneOutboxRecordWithoutRuntimeSideEffect() {
        NodeProperties properties = properties(null, null, null);
        ControlLoopStore store = new ControlLoopStore(
                temporaryDirectory, "node_alpha", MAPPER, Clock.systemUTC());
        store.storeCommand(start());
        FakeSessionRuntimeAdapter runtime = new FakeSessionRuntimeAdapter("FLOW", Duration.ofMillis(20), MAPPER);
        RunSupervisor supervisor = new RunSupervisor(properties, store, runtime);
        FakeService service = new FakeService();
        service.duplicateAck = true;
        try (runtime; supervisor;
                ServiceConnectionLoop loop = new ServiceConnectionLoop(
                        properties, store, service, runtime, supervisor)) {
            loop.flushOutbox();
            assertThat(store.pendingEvents(10)).isEmpty();
            assertThat(service.events).hasSize(1);
        }
    }

    @Test
    void proxyTlsAndStatusPreflightClassificationsFailClosedWithoutSecrets() throws Exception {
        Path proxyPassword = temporaryDirectory.resolve("proxy.secret");
        Files.writeString(proxyPassword, "private-password");
        NodeProperties proxyProperties = properties(
                URI.create("http://proxy-user@proxy.example:8080"), proxyPassword, null);

        assertThat(NodeHttpClientFactory.create(proxyProperties)).isNotNull();
        assertThatThrownBy(() -> NodeHttpClientFactory.create(properties(
                null, null, temporaryDirectory.resolve("missing.p12"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStorePasswordFile");
        assertThat(HttpsControlLoopProtocolClient.rejectedStatus(401).isRetryable()).isFalse();
        assertThat(HttpsControlLoopProtocolClient.rejectedStatus(403).isRetryable()).isFalse();
        assertThat(HttpsControlLoopProtocolClient.rejectedStatus(407).isRetryable()).isTrue();
        assertThat(HttpsControlLoopProtocolClient.classifyTransportFailure(
                new java.io.IOException("certificate handshake failed"))).isEqualTo("TLS preflight failed");
        assertThat(HttpsControlLoopProtocolClient.rejectedStatus(407).getMessage())
                .doesNotContain("private-password");
    }

    private NodeProperties properties(URI proxyUri, Path proxyPasswordFile, Path trustStore) {
        return new NodeProperties(
                "node_alpha", "Node Alpha", URI.create("https://service.example/"), "x".repeat(32),
                temporaryDirectory, Duration.ofMillis(20), Duration.ofSeconds(2), "fake-session", "ws",
                Map.of("workspace_main", temporaryDirectory.resolve("workspace")), Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(1), proxyUri,
                proxyPasswordFile, trustStore, null, "FLOW", Duration.ofMillis(200));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class FakeService implements ControlLoopProtocolClient {

        private final List<PollResult> pollResults = new ArrayList<>();
        private final List<DurableNodeEvent> events = new ArrayList<>();
        private int pollCount;
        private boolean offline;
        private boolean duplicateAck;

        @Override
        public ProtocolAck hello(Set<String> capabilities) {
            return new ProtocolAck("hello", false);
        }

        @Override
        public ProtocolAck heartbeat(String activeRunId, String activeRunState) {
            return new ProtocolAck("heartbeat", false);
        }

        @Override
        public PollResult poll() {
            int index = pollCount++;
            return index < pollResults.size() ? pollResults.get(index) : new PollResult.NoCommand();
        }

        @Override
        public ProtocolAck sendEvent(DurableNodeEvent event) {
            if (offline) {
                throw new ProtocolClientException("offline", true);
            }
            events.add(event);
            return new ProtocolAck(event.messageId(), duplicateAck);
        }
    }
}
