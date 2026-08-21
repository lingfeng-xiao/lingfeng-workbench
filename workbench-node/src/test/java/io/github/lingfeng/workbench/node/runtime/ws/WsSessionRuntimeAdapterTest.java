package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodeClient;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodePromptTarget;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.MissionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WsSessionRuntimeAdapterTest {

    private static final OpenCodePromptTarget PROMPT_TARGET =
            new OpenCodePromptTarget("build", "workspace", "gpt-5.6-luna");

    @TempDir
    Path temporaryDirectory;

    @Test
    void endpointGateRejectsNonLoopbackAndVersionMismatch() {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        assertThatThrownBy(() -> new WsEndpointResolver(
                URI.create("http://192.0.2.1:4096"), "0.0.0--test", client, PROMPT_TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");

        WsEndpointResolver mismatch = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "wrong-version", client, PROMPT_TARGET);
        assertThat(mismatch.probe().available()).isFalse();
    }

    @Test
    void sendsOneNativePromptAndTreatsIdleAsRuntimeNotBusinessAcceptance() throws Exception {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        WsEndpointResolver resolver = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "0.0.0--test", client, PROMPT_TARGET);
        List<NormalizedRuntimeEvent> events = new CopyOnWriteArrayList<>();
        Files.createDirectories(temporaryDirectory.resolve("evidence"));
        try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(
                client, resolver, PROMPT_TARGET, MAPPER, Duration.ofSeconds(30), Duration.ofMillis(10))) {
            SessionContext context = new SessionContext(
                    start(), temporaryDirectory, temporaryDirectory.resolve("evidence"));
            SessionHandle session = adapter.openSession(context, events::add).toCompletableFuture().join();
            adapter.submitMission(session, new MissionInput(
                    "objective", "acceptance", "side effects", "profile"), events::add)
                    .toCompletableFuture().join();

            client.emit(status("busy"));
            client.statuses = Map.of();
            client.emit(status("idle"));

            assertThat(client.prompts).hasSize(1);
            assertThat(client.promptTarget).isEqualTo(PROMPT_TARGET);
            assertThat(events).anyMatch(NormalizedRuntimeEvent.MissionAccepted.class::isInstance)
                    .anyMatch(NormalizedRuntimeEvent.RuntimeIdle.class::isInstance);
            assertThat(events).noneMatch(event -> event.getClass().getSimpleName().equals("Terminal"));
            assertThat(temporaryDirectory.resolve("evidence/runtime-events.ndjson")).isNotEmptyFile();
            assertThat(temporaryDirectory.resolve("evidence/conversation.ndjson")).isNotEmptyFile();
        }
    }

    @Test
    void mapsNativePermissionAndAbortWithoutCustomPauseProtocol() throws Exception {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        WsEndpointResolver resolver = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "0.0.0--test", client, PROMPT_TARGET);
        List<NormalizedRuntimeEvent> events = new CopyOnWriteArrayList<>();
        Files.createDirectories(temporaryDirectory.resolve("evidence"));
        try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(
                client, resolver, PROMPT_TARGET, MAPPER, Duration.ofSeconds(30), Duration.ofMillis(10))) {
            SessionContext context = new SessionContext(
                    start(), temporaryDirectory, temporaryDirectory.resolve("evidence"));
            SessionHandle session = adapter.openSession(context, events::add).toCompletableFuture().join();
            client.emit(MAPPER.readTree("""
                    {"type":"permission.asked","properties":{"id":"per_001","sessionID":"ses_001","permission":"bash"}}
                    """));

            adapter.provideInteractionResponse(session,
                    new InteractionInput("cmd", "per_001", "per_001",
                            io.github.lingfeng.workbench.node.protocol.v2.NodeCommand.Decision.APPROVE, "approve"),
                    events::add).toCompletableFuture().join();
            adapter.cancel(session, "stop", events::add).toCompletableFuture().join();

            assertThat(events).anyMatch(NormalizedRuntimeEvent.InteractionRequested.class::isInstance);
            assertThat(client.permissionReplies).containsExactly("per_001:true");
            assertThat(client.aborted).isTrue();
        }
    }

    @Test
    void suppressesIdleAndErrorEventsThatRaceWithAbort() throws Exception {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        WsEndpointResolver resolver = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "0.0.0--test", client, PROMPT_TARGET);
        List<NormalizedRuntimeEvent> events = new CopyOnWriteArrayList<>();
        Files.createDirectories(temporaryDirectory.resolve("evidence"));
        try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(
                client, resolver, PROMPT_TARGET, MAPPER, Duration.ofSeconds(30), Duration.ofMillis(10))) {
            SessionContext context = new SessionContext(
                    start(), temporaryDirectory, temporaryDirectory.resolve("evidence"));
            SessionHandle session = adapter.openSession(context, events::add).toCompletableFuture().join();
            adapter.submitMission(session, new MissionInput(
                    "objective", "acceptance", "side effects", "profile"), events::add)
                    .toCompletableFuture().join();
            client.emitAbortEvents = true;

            adapter.cancel(session, "stop", events::add).toCompletableFuture().join();

            assertThat(client.aborted).isTrue();
            assertThat(events).anyMatch(event -> event instanceof NormalizedRuntimeEvent.StatusChanged status
                            && status.status() == io.github.lingfeng.workbench.node.runtime.session.RuntimeStatus.ABORTED)
                    .noneMatch(NormalizedRuntimeEvent.RuntimeIdle.class::isInstance)
                    .noneMatch(NormalizedRuntimeEvent.SessionFailed.class::isInstance);
        }
    }

    @Test
    void reconnectsWithBackoffAndEmitsOneWarningWhileTheEndpointStaysUnavailable() throws Exception {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        WsEndpointResolver resolver = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "0.0.0--test", client, PROMPT_TARGET);
        List<NormalizedRuntimeEvent> events = new CopyOnWriteArrayList<>();
        Files.createDirectories(temporaryDirectory.resolve("evidence"));
        try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(
                client, resolver, PROMPT_TARGET, MAPPER, Duration.ofSeconds(30), Duration.ofMillis(10))) {
            SessionContext context = new SessionContext(
                    start(), temporaryDirectory, temporaryDirectory.resolve("evidence"));
            adapter.openSession(context, events::add).toCompletableFuture().join();
            client.failSubscriptions = true;

            client.disconnect();
            Thread.sleep(180);

            assertThat(client.subscribeCalls.get()).isBetween(4, 6);
            assertThat(events.stream()
                    .filter(event -> event instanceof NormalizedRuntimeEvent.ProgressUpdated progress
                            && progress.summary().equals(
                            "OpenCode event stream disconnected; reconciling native state"))
                    .count()).isEqualTo(1);
        }
    }

    @Test
    void doesNotTreatAMissingStatusEntryAsIdleWhileTheAssistantIsStillRunning() throws Exception {
        FakeOpenCodeClient client = new FakeOpenCodeClient(temporaryDirectory);
        WsEndpointResolver resolver = new WsEndpointResolver(
                URI.create("http://127.0.0.1:54014"), "0.0.0--test", client, PROMPT_TARGET);
        List<NormalizedRuntimeEvent> events = new CopyOnWriteArrayList<>();
        Files.createDirectories(temporaryDirectory.resolve("evidence"));
        try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(
                client, resolver, PROMPT_TARGET, MAPPER, Duration.ofSeconds(30), Duration.ofMillis(10))) {
            SessionContext context = new SessionContext(
                    start(), temporaryDirectory, temporaryDirectory.resolve("evidence"));
            SessionHandle session = adapter.openSession(context, events::add).toCompletableFuture().join();
            adapter.submitMission(session, new MissionInput(
                    "objective", "acceptance", "side effects", "profile"), events::add)
                    .toCompletableFuture().join();
            client.statuses = Map.of();
            client.messageHistory = List.of(MAPPER.readTree("""
                    {"info":{"role":"assistant","time":{"created":1}},"parts":[]}
                    """));

            client.disconnect();
            Thread.sleep(40);

            assertThat(events).noneMatch(NormalizedRuntimeEvent.RuntimeIdle.class::isInstance);

            client.messageHistory = List.of(MAPPER.readTree("""
                    {"info":{"role":"assistant","finish":"stop","time":{"created":1,"completed":2}},
                     "parts":[{"type":"text","text":"finished"}]}
                    """));
            client.disconnect();
            Thread.sleep(60);

            assertThat(events).anyMatch(NormalizedRuntimeEvent.RuntimeIdle.class::isInstance);
        }
    }

    private static JsonNode status(String type) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "session.status");
        ObjectNode properties = event.putObject("properties");
        properties.put("sessionID", "ses_001");
        properties.putObject("status").put("type", type);
        return event;
    }

    private static final class FakeOpenCodeClient implements OpenCodeClient {
        private final Path workspace;
        private final List<String> prompts = new ArrayList<>();
        private final List<String> permissionReplies = new ArrayList<>();
        private final AtomicInteger subscribeCalls = new AtomicInteger();
        private Consumer<JsonNode> sink = ignored -> { };
        private Consumer<Throwable> failureSink = ignored -> { };
        private Map<String, String> statuses = Map.of("ses_001", "busy");
        private volatile List<JsonNode> messageHistory;
        private OpenCodePromptTarget promptTarget;
        private boolean aborted;
        private boolean emitAbortEvents;
        private volatile boolean failSubscriptions;

        private FakeOpenCodeClient(Path workspace) {
            this.workspace = workspace;
        }

        void emit(JsonNode event) {
            sink.accept(event);
        }

        void disconnect() {
            failureSink.accept(new IllegalStateException("endpoint unavailable"));
        }

        @Override public Health health() { return new Health(true, "0.0.0--test"); }
        @Override public Session createSession(Path directory, String title) {
            return new Session("ses_001", workspace.toAbsolutePath().normalize().toString());
        }
        @Override public Session getSession(Path directory, String sessionId) {
            return new Session(sessionId, workspace.toAbsolutePath().normalize().toString());
        }
        @Override public Map<String, String> sessionStatuses(Path directory) { return statuses; }
        @Override public List<JsonNode> messages(Path directory, String sessionId) {
            if (messageHistory != null) {
                return messageHistory;
            }
            try {
                return List.of(MAPPER.readTree("""
                        {"info":{"role":"assistant"},"parts":[{"type":"text","text":"runtime done"}]}
                        """));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
        @Override public List<JsonNode> permissions(Path directory) { return List.of(); }
        @Override public List<JsonNode> questions(Path directory) { return List.of(); }
        @Override public boolean supportsPromptTarget(OpenCodePromptTarget target) {
            return PROMPT_TARGET.equals(target);
        }
        @Override public void promptAsync(
                Path directory, String sessionId, OpenCodePromptTarget target, String prompt) {
            promptTarget = target;
            prompts.add(prompt);
        }
        @Override public void replyPermission(Path directory, String requestId, boolean approved) {
            permissionReplies.add(requestId + ":" + approved);
        }
        @Override public void replyQuestion(Path directory, String requestId, String answer) { }
        @Override public void rejectQuestion(Path directory, String requestId) { }
        @Override public void abort(Path directory, String sessionId) {
            aborted = true;
            if (emitAbortEvents) {
                try {
                    emit(MAPPER.readTree("""
                            {"type":"session.error","properties":{"sessionID":"ses_001"}}
                            """));
                    statuses = Map.of();
                    emit(status("idle"));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        }
        @Override public Subscription subscribe(
                Path directory, Consumer<JsonNode> eventSink, Consumer<Throwable> failureSink) {
            subscribeCalls.incrementAndGet();
            sink = eventSink;
            this.failureSink = failureSink;
            if (failSubscriptions) {
                Thread.ofVirtual().start(() -> failureSink.accept(
                        new IllegalStateException("endpoint unavailable")));
            }
            return () -> sink = ignored -> { };
        }
        @Override public void close() { }
    }
}
