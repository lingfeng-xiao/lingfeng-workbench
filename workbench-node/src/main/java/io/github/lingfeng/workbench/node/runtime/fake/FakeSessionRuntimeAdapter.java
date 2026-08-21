package io.github.lingfeng.workbench.node.runtime.fake;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.MissionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.RuntimeStatus;
import io.github.lingfeng.workbench.node.runtime.session.SessionCapabilities;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class FakeSessionRuntimeAdapter implements SessionRuntimeAdapter, AutoCloseable {

    private final String scenario;
    private final Duration delay;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    public FakeSessionRuntimeAdapter(String scenario, Duration delay, ObjectMapper objectMapper) {
        this(scenario, delay, objectMapper, null);
    }

    public FakeSessionRuntimeAdapter(
            String scenario, Duration delay, ObjectMapper objectMapper, String ignoredMissionDigestOverride) {
        this.scenario = scenario;
        this.delay = delay;
    }

    @Override
    public CompletionStage<RuntimeProbe> probe() {
        return CompletableFuture.completedFuture(new RuntimeProbe(true, "Fake native Session Runtime ready"));
    }

    @Override
    public SessionCapabilities capabilities() {
        return new SessionCapabilities("fake-session", Set.of(
                "session", "event-stream", "permission", "question", "abort", "resume"));
    }

    @Override
    public CompletionStage<SessionHandle> openSession(
            SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink) {
        String id = "fake-session:" + context.command().binding().runId();
        sessions.put(id, context);
        eventSink.accept(new NormalizedRuntimeEvent.SessionOpened(true));
        return CompletableFuture.completedFuture(new SessionHandle(
                id, "fake-session", "test", context.workspace().toString()));
    }

    @Override
    public CompletionStage<Void> submitMission(
            SessionHandle session, MissionInput mission, Consumer<NormalizedRuntimeEvent> eventSink) {
        requireSession(session);
        eventSink.accept(new NormalizedRuntimeEvent.MissionAccepted());
        eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.BUSY, "Fake Session is busy"));
        eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated("Fake Mission contract accepted"));
        scheduler.schedule(() -> {
            eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated(
                    "Fake Mission reached its completion checkpoint"));
            if (scenario.equals("INTERACTION")) {
                eventSink.accept(new NormalizedRuntimeEvent.InteractionRequested(
                        "int_001", "cp_001", "Approve deterministic fake execution",
                        Set.of("APPROVE", "REJECT"), true));
            } else {
                emitIdle(eventSink);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> provideInteractionResponse(
            SessionHandle session,
            InteractionInput response,
            Consumer<NormalizedRuntimeEvent> eventSink) {
        requireSession(session);
        scheduler.schedule(() -> emitIdle(eventSink), delay.toMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<SessionInspection> reattach(
            SessionHandle session,
            SessionContext context,
            Consumer<NormalizedRuntimeEvent> eventSink) {
        sessions.putIfAbsent(session.opaqueReference(), context);
        if (scenario.equals("INTERACTION")) {
            eventSink.accept(new NormalizedRuntimeEvent.InteractionRequested(
                    "int_001", "cp_001", "Approve deterministic fake execution",
                    Set.of("APPROVE", "REJECT"), true));
            return CompletableFuture.completedFuture(new SessionInspection(
                    true, true, RuntimeStatus.WAITING_INTERACTION, "Fake Session reattached"));
        }
        return CompletableFuture.completedFuture(new SessionInspection(
                true, true, RuntimeStatus.BUSY, "Fake Session reattached"));
    }

    @Override
    public CompletionStage<Void> cancel(
            SessionHandle session, String reasonSummary, Consumer<NormalizedRuntimeEvent> eventSink) {
        requireSession(session);
        eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.ABORTED, reasonSummary));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> closeSession(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
        sessions.remove(session.opaqueReference());
        eventSink.accept(new NormalizedRuntimeEvent.SessionClosed());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        sessions.clear();
    }

    private void requireSession(SessionHandle session) {
        if (!sessions.containsKey(session.opaqueReference())) {
            throw new IllegalArgumentException("Unknown fake Session");
        }
    }

    private static void emitIdle(Consumer<NormalizedRuntimeEvent> eventSink) {
        eventSink.accept(new NormalizedRuntimeEvent.StatusChanged(RuntimeStatus.IDLE, "Fake Session is idle"));
        eventSink.accept(new NormalizedRuntimeEvent.RuntimeIdle("Fake Mission execution finished"));
    }
}
