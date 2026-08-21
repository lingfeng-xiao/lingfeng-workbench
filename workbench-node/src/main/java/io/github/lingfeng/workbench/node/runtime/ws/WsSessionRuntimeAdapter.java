package io.github.lingfeng.workbench.node.runtime.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.evidence.BoundedEvidenceWriter;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodeClient;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodePromptTarget;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.MissionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.RuntimeStatus;
import io.github.lingfeng.workbench.node.runtime.session.SessionCapabilities;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class WsSessionRuntimeAdapter implements SessionRuntimeAdapter, AutoCloseable {

    private static final SessionCapabilities CAPABILITIES = new SessionCapabilities(
            "ws", Set.of("session", "event-stream", "permission", "question", "abort", "resume"));

    private final OpenCodeClient client;
    private final WsEndpointResolver endpointResolver;
    private final OpenCodePromptTarget promptTarget;
    private final ObjectMapper objectMapper;
    private final Duration reconnectDelay;
    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();

    public WsSessionRuntimeAdapter(
            OpenCodeClient client,
            WsEndpointResolver endpointResolver,
            OpenCodePromptTarget promptTarget,
            ObjectMapper objectMapper,
            Duration reconcileInterval,
            Duration reconnectDelay) {
        this.client = client;
        this.endpointResolver = endpointResolver;
        this.promptTarget = promptTarget;
        this.objectMapper = objectMapper;
        this.reconnectDelay = reconnectDelay;
        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("ws-reconcile").unstarted(runnable));
        scheduler.scheduleWithFixedDelay(this::reconcileAll,
                reconcileInterval.toMillis(), reconcileInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletionStage<RuntimeProbe> probe() {
        return CompletableFuture.supplyAsync(endpointResolver::probe, ioExecutor);
    }

    @Override
    public SessionCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<SessionHandle> openSession(
            SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.supplyAsync(() -> {
            String title = "lingfeng " + context.command().binding().runId();
            OpenCodeClient.Session created = client.createSession(context.workspace(), title);
            ManagedSession managed = new ManagedSession(context, eventSink);
            if (sessions.putIfAbsent(created.id(), managed) != null) {
                throw new IllegalStateException("OpenCode returned a duplicate active Session ID");
            }
            subscribe(created.id(), managed);
            eventSink.accept(new NormalizedRuntimeEvent.SessionOpened(true));
            return new SessionHandle(
                    created.id(), endpointResolver.baseUri().toString(), endpointResolver.expectedVersion(),
                    context.workspace().toString());
        }, ioExecutor);
    }

    @Override
    public CompletionStage<Void> submitMission(
            SessionHandle session, MissionInput mission, Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.runAsync(() -> {
            ManagedSession managed = requireSession(session);
            if (!managed.promptSubmitted.compareAndSet(false, true)) {
                throw new IllegalStateException("Mission prompt was already submitted");
            }
            try {
                client.promptAsync(
                        managed.context.workspace(), session.opaqueReference(), promptTarget,
                        prompt(mission, managed.context.contextPaths()));
                eventSink.accept(new NormalizedRuntimeEvent.MissionAccepted());
                eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(
                        RuntimeStatus.BUSY, "OpenCode accepted the Mission prompt"));
            } catch (RuntimeException exception) {
                managed.promptSubmitted.set(false);
                throw exception;
            }
        }, ioExecutor);
    }

    @Override
    public CompletionStage<Void> provideInteractionResponse(
            SessionHandle session,
            InteractionInput response,
            Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.runAsync(() -> {
            ManagedSession managed = requireSession(session);
            InteractionKind kind = managed.interactions.get(response.interactionId());
            if (kind == null) {
                reconcileInteractions(session.opaqueReference(), managed);
                kind = managed.interactions.get(response.interactionId());
            }
            if (kind == null) {
                throw new IllegalStateException("OpenCode Interaction is no longer pending");
            }
            if (kind == InteractionKind.PERMISSION) {
                if (response.decision() == NodeCommand.Decision.PROVIDE_INPUT) {
                    throw new IllegalArgumentException("Permission does not accept free-form input");
                }
                client.replyPermission(managed.context.workspace(), response.interactionId(),
                        response.decision() == NodeCommand.Decision.APPROVE);
            } else if (response.decision() == NodeCommand.Decision.REJECT) {
                client.rejectQuestion(managed.context.workspace(), response.interactionId());
            } else if (response.decision() == NodeCommand.Decision.PROVIDE_INPUT) {
                client.replyQuestion(managed.context.workspace(), response.interactionId(), response.responseSummary());
            } else {
                throw new IllegalArgumentException("Question requires PROVIDE_INPUT or REJECT");
            }
            managed.interactions.remove(response.interactionId());
        }, ioExecutor);
    }

    @Override
    public CompletionStage<SessionInspection> reattach(
            SessionHandle session,
            SessionContext context,
            Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.supplyAsync(() -> {
            if (!session.runtimeIdentity().equals(endpointResolver.baseUri().toString())
                    || !session.runtimeVersion().equals(endpointResolver.expectedVersion())
                    || !Path.of(session.workspaceDirectory()).toAbsolutePath().normalize().equals(context.workspace())) {
                return new SessionInspection(false, false, RuntimeStatus.ERROR,
                        "OpenCode server or workspace binding changed");
            }
            OpenCodeClient.Session existing = client.getSession(context.workspace(), session.opaqueReference());
            boolean sameSession = existing.id().equals(session.opaqueReference())
                    && (existing.directory().isBlank()
                    || Path.of(existing.directory()).toAbsolutePath().normalize().equals(context.workspace()));
            if (!sameSession) {
                return new SessionInspection(false, true, RuntimeStatus.ERROR, "OpenCode Session binding changed");
            }
            ManagedSession managed = new ManagedSession(context, eventSink);
            managed.promptSubmitted.set(true);
            ManagedSession selected = sessions.putIfAbsent(existing.id(), managed);
            if (selected == null) {
                subscribe(existing.id(), managed);
                selected = managed;
            }
            RuntimeStatus status = reconcile(existing.id(), selected);
            return new SessionInspection(true, true, status, "OpenCode Session reattached");
        }, ioExecutor);
    }

    @Override
    public CompletionStage<Void> cancel(
            SessionHandle session, String reasonSummary, Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.runAsync(() -> {
            ManagedSession managed = requireSession(session);
            managed.aborted.set(true);
            client.abort(managed.context.workspace(), session.opaqueReference());
            eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.ABORTED, reasonSummary));
        }, ioExecutor);
    }

    @Override
    public CompletionStage<Void> closeSession(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
        return CompletableFuture.runAsync(() -> {
            ManagedSession removed = sessions.remove(session.opaqueReference());
            if (removed != null) {
                removed.closed.set(true);
                OpenCodeClient.Subscription subscription = removed.subscription;
                if (subscription != null) {
                    subscription.close();
                }
                eventSink.accept(new NormalizedRuntimeEvent.SessionClosed());
            }
        }, ioExecutor);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        sessions.values().forEach(session -> {
            session.closed.set(true);
            if (session.subscription != null) {
                session.subscription.close();
            }
        });
        sessions.clear();
        client.close();
        ioExecutor.close();
    }

    private void subscribe(String sessionId, ManagedSession managed) {
        managed.subscription = client.subscribe(managed.context.workspace(),
                event -> handleEvent(sessionId, managed, event),
                failure -> handleStreamFailure(sessionId, managed, failure));
    }

    private void handleStreamFailure(String sessionId, ManagedSession managed, Throwable failure) {
        if (managed.closed.get() || !sessions.containsKey(sessionId)) {
            return;
        }
        if (!managed.reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        if (managed.disconnectNoticeEmitted.compareAndSet(false, true)) {
            managed.eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated(
                    "OpenCode event stream disconnected; reconciling native state"));
        }
        long delayMillis = nextReconnectDelayMillis(managed);
        scheduler.schedule(() -> {
            if (!managed.closed.get() && sessions.get(sessionId) == managed) {
                managed.reconnectScheduled.set(false);
                try {
                    subscribe(sessionId, managed);
                    reconcile(sessionId, managed);
                } catch (RuntimeException exception) {
                    handleStreamFailure(sessionId, managed, exception);
                }
            } else {
                managed.reconnectScheduled.set(false);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void handleEvent(String sessionId, ManagedSession managed, JsonNode rawEvent) {
        recordRawEvent(managed, rawEvent);
        JsonNode event = rawEvent.has("payload") ? rawEvent.path("payload") : rawEvent;
        String type = event.path("type").asText();
        JsonNode properties = event.path("properties");
        String eventSession = properties.path("sessionID").asText(
                properties.path("part").path("sessionID").asText());
        if (!eventSession.isBlank() && !eventSession.equals(sessionId)) {
            return;
        }
        if (!eventSession.isBlank()) {
            managed.reconnectAttempts.set(0);
            managed.disconnectNoticeEmitted.set(false);
        }
        switch (type) {
            case "session.status" -> handleStatus(
                    sessionId, managed, properties.path("status").path("type").asText());
            case "session.idle" -> emitIdle(sessionId, managed);
            case "permission.asked", "permission.updated" ->
                    projectPermission(sessionId, managed, properties);
            case "question.asked" -> projectQuestion(sessionId, managed, properties);
            case "message.part.updated" -> projectProgress(managed, properties);
            case "session.error" -> {
                if (!managed.aborted.get()) {
                    managed.eventSink.accept(
                            new NormalizedRuntimeEvent.SessionFailed("OpenCode reported a Session error"));
                }
            }
            default -> {
                // Raw evidence is preserved; unknown upstream events are intentionally not projected.
            }
        }
    }

    private void handleStatus(String sessionId, ManagedSession managed, String type) {
        switch (type) {
            case "busy" -> managed.eventSink.accept(
                    new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.BUSY, "OpenCode Session is busy"));
            case "retry" -> managed.eventSink.accept(
                    new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.RETRY, "OpenCode Session is retrying"));
            case "idle" -> emitIdle(sessionId, managed);
            default -> managed.eventSink.accept(
                    new NormalizedRuntimeEvent.SessionFailed("OpenCode emitted an unknown Session status"));
        }
    }

    private RuntimeStatus reconcile(String sessionId, ManagedSession managed) {
        client.getSession(managed.context.workspace(), sessionId);
        Map<String, String> statuses = client.sessionStatuses(managed.context.workspace());
        reconcileInteractions(sessionId, managed);
        String nativeStatus = statuses.get(sessionId);
        if (nativeStatus == null) {
            if (managed.promptSubmitted.get()
                    && hasCompletedAssistantResponse(
                    client.messages(managed.context.workspace(), sessionId))) {
                emitIdle(sessionId, managed);
                return RuntimeStatus.IDLE;
            }
            return managed.promptSubmitted.get() ? RuntimeStatus.BUSY : RuntimeStatus.IDLE;
        }
        if (nativeStatus.equals("idle")) {
            if (managed.promptSubmitted.get()) {
                emitIdle(sessionId, managed);
            }
            return RuntimeStatus.IDLE;
        }
        handleStatus(sessionId, managed, nativeStatus);
        return switch (nativeStatus) {
            case "busy" -> RuntimeStatus.BUSY;
            case "retry" -> RuntimeStatus.RETRY;
            default -> RuntimeStatus.ERROR;
        };
    }

    private void reconcileInteractions(String sessionId, ManagedSession managed) {
        for (JsonNode permission : client.permissions(managed.context.workspace())) {
            if (permission.path("sessionID").asText().equals(sessionId)) {
                projectPermission(sessionId, managed, permission);
            }
        }
        for (JsonNode question : client.questions(managed.context.workspace())) {
            if (question.path("sessionID").asText().equals(sessionId)) {
                projectQuestion(sessionId, managed, question);
            }
        }
    }

    private void projectPermission(String sessionId, ManagedSession managed, JsonNode properties) {
        JsonNode request = properties.has("id") ? properties : properties.path("permission");
        if (!request.path("sessionID").asText(sessionId).equals(sessionId)) {
            return;
        }
        String id = request.path("id").asText(request.path("requestID").asText());
        if (id.isBlank() || managed.interactions.putIfAbsent(id, InteractionKind.PERMISSION) != null) {
            return;
        }
        String summary = request.path("title").asText(request.path("permission").asText("Permission required"));
        managed.eventSink.accept(new NormalizedRuntimeEvent.InteractionRequested(
                id, id, summary, Set.of("APPROVE", "REJECT"), true));
    }

    private void projectQuestion(String sessionId, ManagedSession managed, JsonNode request) {
        if (!request.path("sessionID").asText(sessionId).equals(sessionId)) {
            return;
        }
        String id = request.path("id").asText(request.path("requestID").asText());
        if (id.isBlank() || managed.interactions.putIfAbsent(id, InteractionKind.QUESTION) != null) {
            return;
        }
        String summary = "OpenCode requested input";
        JsonNode questions = request.path("questions");
        if (questions.isArray() && !questions.isEmpty()) {
            summary = questions.get(0).path("question").asText(
                    questions.get(0).path("header").asText(summary));
        }
        managed.eventSink.accept(new NormalizedRuntimeEvent.InteractionRequested(
                id, id, summary, Set.of("PROVIDE_INPUT", "REJECT"), true));
    }

    private void projectProgress(ManagedSession managed, JsonNode properties) {
        String summary = properties.path("delta").asText();
        if (summary.isBlank()) {
            summary = properties.path("part").path("text").asText();
        }
        if (!summary.isBlank()) {
            managed.eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated(compact(summary)));
        }
    }

    private void emitIdle(String sessionId, ManagedSession managed) {
        if (!managed.promptSubmitted.get() || managed.aborted.get()
                || !managed.idleEmitted.compareAndSet(false, true)) {
            return;
        }
        try {
            List<JsonNode> messages = client.messages(managed.context.workspace(), sessionId);
            String summary = lastAssistantText(messages);
            recordConversation(managed, messages);
            managed.eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(
                    RuntimeStatus.IDLE, "OpenCode Session is idle"));
            managed.eventSink.accept(new NormalizedRuntimeEvent.RuntimeIdle(summary));
        } catch (RuntimeException exception) {
            managed.idleEmitted.set(false);
            throw exception;
        }
    }

    private void reconcileAll() {
        sessions.forEach((sessionId, managed) -> {
            if (!managed.closed.get() && !managed.idleEmitted.get()) {
                try {
                    reconcile(sessionId, managed);
                    managed.reconcileWarningEmitted.set(false);
                } catch (RuntimeException exception) {
                    if (managed.reconcileWarningEmitted.compareAndSet(false, true)) {
                        managed.eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated(
                                "OpenCode reconciliation failed; retrying"));
                    }
                }
            }
        });
    }

    private long nextReconnectDelayMillis(ManagedSession managed) {
        int exponent = Math.min(managed.reconnectAttempts.getAndIncrement(), 5);
        long baseMillis = reconnectDelay.toMillis();
        long maximumMillis = Math.max(baseMillis, Duration.ofSeconds(30).toMillis());
        long multiplier = 1L << exponent;
        return baseMillis > maximumMillis / multiplier
                ? maximumMillis
                : Math.min(baseMillis * multiplier, maximumMillis);
    }

    private ManagedSession requireSession(SessionHandle handle) {
        ManagedSession session = sessions.get(handle.opaqueReference());
        if (session == null) {
            throw new IllegalArgumentException("Unknown OpenCode Session handle");
        }
        return session;
    }

    private void recordRawEvent(ManagedSession managed, JsonNode event) {
        try {
            BoundedEvidenceWriter.appendLine(
                    managed.context.evidenceDirectory().resolve("runtime-events.ndjson"),
                    objectMapper.writeValueAsString(event));
        } catch (IOException exception) {
            managed.eventSink.accept(new NormalizedRuntimeEvent.SessionFailed(
                    "OpenCode event evidence could not be persisted"));
        }
    }

    private void recordConversation(ManagedSession managed, List<JsonNode> messages) {
        for (JsonNode message : messages) {
            try {
                BoundedEvidenceWriter.appendLine(
                        managed.context.evidenceDirectory().resolve("conversation.ndjson"),
                        objectMapper.writeValueAsString(message));
            } catch (IOException exception) {
                managed.eventSink.accept(new NormalizedRuntimeEvent.SessionFailed(
                        "OpenCode message evidence could not be persisted"));
                return;
            }
        }
    }

    private static String prompt(MissionInput mission, List<Path> contextPaths) {
        String contextSection = contextPaths.isEmpty()
                ? "No additional local context paths were selected."
                : "Authorized local context paths:\n" + contextPaths.stream()
                        .map(path -> "- " + path)
                        .collect(java.util.stream.Collectors.joining("\n"));
        return """
                Execute this Mission in the configured workspace.

                Objective:
                %s

                Acceptance criteria:
                %s

                Authorized side effects:
                %s

                Execution profile: %s

                %s

                Use native permission or question requests whenever external approval or input is required.
                Do not claim business acceptance; the Node evaluates acceptance independently from this session.
                """.formatted(mission.objective(), mission.acceptanceSummary(),
                mission.authorizedSideEffectsSummary(), mission.executionProfile(), contextSection);
    }

    private static String lastAssistantText(List<JsonNode> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!message.path("info").path("role").asText().equals("assistant")) {
                continue;
            }
            JsonNode parts = message.path("parts");
            if (parts.isArray()) {
                for (int partIndex = parts.size() - 1; partIndex >= 0; partIndex--) {
                    String text = parts.get(partIndex).path("text").asText();
                    if (!text.isBlank()) {
                        return compact(text);
                    }
                }
            }
        }
        return "OpenCode Session became idle without an assistant text summary";
    }

    private static boolean hasCompletedAssistantResponse(List<JsonNode> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode info = messages.get(index).path("info");
            if (!info.path("role").asText().equals("assistant")) {
                continue;
            }
            return info.path("time").hasNonNull("completed")
                    && !info.path("finish").asText().equals("tool-calls");
        }
        return false;
    }

    private static String compact(String value) {
        String compact = value.trim().replaceAll("\\s+", " ");
        return compact.length() <= 800 ? compact : compact.substring(0, 797) + "...";
    }

    private enum InteractionKind {
        PERMISSION,
        QUESTION
    }

    private static final class ManagedSession {
        private final SessionContext context;
        private final Consumer<NormalizedRuntimeEvent> eventSink;
        private final AtomicBoolean promptSubmitted = new AtomicBoolean();
        private final AtomicBoolean idleEmitted = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private final AtomicBoolean disconnectNoticeEmitted = new AtomicBoolean();
        private final AtomicBoolean reconcileWarningEmitted = new AtomicBoolean();
        private final AtomicInteger reconnectAttempts = new AtomicInteger();
        private final Map<String, InteractionKind> interactions = new ConcurrentHashMap<>();
        private volatile OpenCodeClient.Subscription subscription;

        private ManagedSession(
                SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink) {
            this.context = context;
            this.eventSink = eventSink;
        }
    }
}
