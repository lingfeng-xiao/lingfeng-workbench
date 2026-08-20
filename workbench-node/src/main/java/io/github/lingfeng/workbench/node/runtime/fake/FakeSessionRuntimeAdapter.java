package io.github.lingfeng.workbench.node.runtime.fake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.evidence.BoundedEvidenceWriter;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionCapabilities;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Deterministic local boundary used by combination tests; it never reads or modifies a source workspace. */
public final class FakeSessionRuntimeAdapter implements SessionRuntimeAdapter, AutoCloseable {

    private final String scenario;
    private final Duration turnDelay;
    private final ObjectMapper objectMapper;
    private final String terminalDigestOverride;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, FakeSession> sessions = new ConcurrentHashMap<>();

    public FakeSessionRuntimeAdapter(String scenario, Duration turnDelay, ObjectMapper objectMapper) {
        this(scenario, turnDelay, objectMapper, null);
    }

    public FakeSessionRuntimeAdapter(
            String scenario,
            Duration turnDelay,
            ObjectMapper objectMapper,
            String terminalDigestOverride) {
        this.scenario = scenario;
        this.turnDelay = turnDelay;
        this.objectMapper = objectMapper;
        this.terminalDigestOverride = terminalDigestOverride;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("fake-session-runtime").unstarted(runnable));
    }

    @Override
    public CompletionStage<RuntimeProbe> probe() {
        return CompletableFuture.completedFuture(new RuntimeProbe(true, "deterministic fake session runtime"));
    }

    @Override
    public SessionCapabilities capabilities() {
        return new SessionCapabilities("fake-session", Set.of(
                "session", "turn", "interaction", "checkpoint", "pause", "resume", "cancel",
                "inspect", "structured-terminal"));
    }

    @Override
    public CompletionStage<SessionHandle> openSession(
            SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink) {
        String reference = "fake-session:" + context.command().binding().runId() + ":"
                + context.command().binding().missionDigest();
        FakeSession created = new FakeSession(
                context.command().binding().missionDigest(), context.evidenceDirectory(), false, false);
        FakeSession existing = sessions.putIfAbsent(reference, created);
        if (existing != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Fake session already exists"));
        }
        appendEvidence(created, "session_opened", "session handle retained locally");
        eventSink.accept(new NormalizedRuntimeEvent.SessionOpened(true));
        return CompletableFuture.completedFuture(new SessionHandle(reference));
    }

    @Override
    public CompletionStage<Void> submitTurn(
            SessionHandle session, TurnInput turn, Consumer<NormalizedRuntimeEvent> eventSink) {
        FakeSession fakeSession = requireSession(session);
        if (fakeSession.cancelled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Fake session is cancelled"));
        }
        eventSink.accept(new NormalizedRuntimeEvent.TurnAccepted(turn.turnId()));
        appendEvidence(fakeSession, "turn_submitted", turn.turnId());
        if (scenario.equals("INTERACTION") && turn.turnNumber() == 1 && !fakeSession.interactionResolved()) {
            eventSink.accept(new NormalizedRuntimeEvent.CheckpointSaved("cp_001"));
            eventSink.accept(new NormalizedRuntimeEvent.InteractionRequested(
                    "int_001", "cp_001", "Approve the next authorized local step?",
                    Set.of("APPROVE", "REJECT"), true));
            appendEvidence(fakeSession, "interaction_requested", "int_001");
            writeCheckpoint(fakeSession, "cp_001", "Approve the next authorized local step?");
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        scheduler.schedule(() -> finishTurn(fakeSession, turn, eventSink, completion),
                turnDelay.toMillis(), TimeUnit.MILLISECONDS);
        return completion;
    }

    @Override
    public CompletionStage<Void> provideInteractionResponse(
            SessionHandle session,
            InteractionInput response,
            Consumer<NormalizedRuntimeEvent> eventSink) {
        FakeSession current = requireSession(session);
        if (!response.interactionId().equals("int_001") || !response.checkpointId().equals("cp_001")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Interaction binding mismatch"));
        }
        if (current.interactionResolved()) {
            return CompletableFuture.completedFuture(null);
        }
        FakeSession resolved = current.withInteractionResolved();
        sessions.put(session.opaqueReference(), resolved);
        appendEvidence(resolved, "interaction_response", response.decision().name());
        eventSink.accept(new NormalizedRuntimeEvent.Resumed(response.checkpointId()));
        eventSink.accept(new NormalizedRuntimeEvent.TurnFinished("turn-1"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> requestCheckpoint(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
        requireSession(session);
        eventSink.accept(new NormalizedRuntimeEvent.CheckpointSaved("cp_runtime"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> pause(SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
        requireSession(session);
        eventSink.accept(new NormalizedRuntimeEvent.Paused("cp_001"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> resume(
            SessionHandle session, String checkpointId, Consumer<NormalizedRuntimeEvent> eventSink) {
        String[] handleParts = session.opaqueReference().split(":", 3);
        String digest = handleParts.length == 3 ? handleParts[2] : "unknown";
        sessions.putIfAbsent(session.opaqueReference(), new FakeSession(digest, null, false, false));
        eventSink.accept(new NormalizedRuntimeEvent.Resumed(checkpointId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> cancel(
            SessionHandle session, String reasonSummary, Consumer<NormalizedRuntimeEvent> eventSink) {
        FakeSession current = requireSession(session);
        sessions.put(session.opaqueReference(), current.withCancelled());
        appendEvidence(current, "cancelled", reasonSummary);
        eventSink.accept(new NormalizedRuntimeEvent.Terminal(
                current.missionDigest(), NormalizedRuntimeEvent.RuntimeOutcome.INTERRUPTED,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN, "Run cancelled by a durable command"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<SessionInspection> inspect(SessionHandle session) {
        boolean recognized = session.opaqueReference().matches("^fake-session:run_[A-Za-z0-9]+:[a-f0-9]{64}$");
        return CompletableFuture.completedFuture(new SessionInspection(
                recognized, recognized, recognized, recognized ? "fake session is resumable" : "unknown handle"));
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
    }

    private void finishTurn(
            FakeSession fakeSession,
            TurnInput turn,
            Consumer<NormalizedRuntimeEvent> eventSink,
            CompletableFuture<Void> completion) {
        try {
            eventSink.accept(new NormalizedRuntimeEvent.PhaseChanged(
                    turn.turnNumber() == 3 ? "REPORTING" : "IMPLEMENTATION",
                    "Fake runtime completed turn " + turn.turnNumber()));
            eventSink.accept(new NormalizedRuntimeEvent.ProgressUpdated(
                    "Turn " + turn.turnNumber() + " completed"));
            eventSink.accept(new NormalizedRuntimeEvent.TurnFinished(turn.turnId()));
            if (turn.turnNumber() == 3) {
                writeResult(fakeSession);
                eventSink.accept(new NormalizedRuntimeEvent.Terminal(
                        terminalDigestOverride == null ? fakeSession.missionDigest() : terminalDigestOverride,
                        NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                        NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                        "Three deterministic turns passed the frozen acceptance checks"));
            }
            completion.complete(null);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
    }

    private FakeSession requireSession(SessionHandle handle) {
        FakeSession session = sessions.get(handle.opaqueReference());
        if (session == null) {
            throw new IllegalStateException("Fake session handle is not active");
        }
        return session;
    }

    private void appendEvidence(FakeSession session, String eventType, String summary) {
        if (session.evidenceDirectory() == null) {
            return;
        }
        ObjectNode line = objectMapper.createObjectNode();
        line.put("recordedAt", Instant.now().toString());
        line.put("eventType", eventType);
        line.put("summary", summary);
        try {
            BoundedEvidenceWriter.appendLine(
                    session.evidenceDirectory().resolve("conversation.ndjson"),
                    objectMapper.writeValueAsString(line));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to preserve fake Runtime evidence", exception);
        }
    }

    private void writeCheckpoint(FakeSession session, String checkpointId, String prompt) {
        if (session.evidenceDirectory() == null) {
            return;
        }
        ObjectNode checkpoint = objectMapper.createObjectNode();
        checkpoint.put("checkpointId", checkpointId);
        checkpoint.put("prompt", prompt);
        try {
            Files.createDirectories(session.evidenceDirectory().resolve("checkpoints"));
            Files.writeString(session.evidenceDirectory().resolve("checkpoints").resolve(checkpointId + ".json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoint),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to preserve fake Runtime checkpoint", exception);
        }
    }

    private static void writeResult(FakeSession session) {
        if (session.evidenceDirectory() == null) {
            return;
        }
        try {
            Files.writeString(session.evidenceDirectory().resolve("result.md"),
                    "# Fake Runtime result\n\nThree deterministic Turns completed with SUCCEEDED/PASSED.\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to preserve fake Runtime result", exception);
        }
    }

    private record FakeSession(
            String missionDigest, Path evidenceDirectory, boolean interactionResolved, boolean cancelled) {

        FakeSession withInteractionResolved() {
            return new FakeSession(missionDigest, evidenceDirectory, true, cancelled);
        }

        FakeSession withCancelled() {
            return new FakeSession(missionDigest, evidenceDirectory, interactionResolved, true);
        }
    }
}
