package io.github.lingfeng.workbench.node.runtime.ws;

import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionCapabilities;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.SessionInspection;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class WsSessionRuntimeAdapter implements SessionRuntimeAdapter, AutoCloseable {

  private final WsRuntimeAdapter runtime;
  private final ExecutorService runtimeExecutor;
  private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

  public WsSessionRuntimeAdapter(WsRuntimeAdapter runtime) {
    this.runtime = runtime;
    this.runtimeExecutor =
        Executors.newSingleThreadExecutor(
            runnable ->
                Thread.ofPlatform().daemon(true).name("ws-runtime-adapter").unstarted(runnable));
  }

  @Override
  public CompletionStage<RuntimeProbe> probe() {
    return CompletableFuture.supplyAsync(runtime::probe, runtimeExecutor);
  }

  @Override
  public SessionCapabilities capabilities() {
    return new SessionCapabilities(
        "ws", Set.of("structured-terminal", "cancel", "multi-turn"));
  }

  @Override
  public CompletionStage<SessionHandle> openSession(
      SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink) {
    String reference = "ws-local:" + context.command().binding().runId();
    SessionState previous = sessions.putIfAbsent(reference, new SessionState(context));
    if (previous != null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("WS Session already exists for this Run"));
    }
    eventSink.accept(new NormalizedRuntimeEvent.SessionOpened(false));
    return CompletableFuture.completedFuture(new SessionHandle(reference));
  }

  @Override
  public CompletionStage<Void> submitTurn(
      SessionHandle session,
      TurnInput turn,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    SessionState state = requireSession(session);
    eventSink.accept(new NormalizedRuntimeEvent.TurnAccepted(turn.turnId()));
    return CompletableFuture.runAsync(
        () -> {
          String sessionId =
              runtime.executeTurn(state.context, turn, state.runtimeSessionId, eventSink);
          state.runtimeSessionId = sessionId;
          eventSink.accept(new NormalizedRuntimeEvent.TurnFinished(turn.turnId()));
        },
        runtimeExecutor);
  }

  @Override
  public CompletionStage<Void> provideInteractionResponse(
      SessionHandle session,
      InteractionInput response,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    return unsupported("WS durable Interaction response is not proven");
  }

  @Override
  public CompletionStage<Void> requestCheckpoint(
      SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
    return unsupported("WS checkpoint capability is not proven");
  }

  @Override
  public CompletionStage<Void> pause(
      SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink) {
    return unsupported("WS pause capability is not proven");
  }

  @Override
  public CompletionStage<Void> resume(
      SessionHandle session,
      String checkpointId,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    return unsupported("WS restart recovery is not proven");
  }

  @Override
  public CompletionStage<Void> cancel(
      SessionHandle session,
      String reasonSummary,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    runtime.cancel();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<SessionInspection> inspect(SessionHandle session) {
    SessionState state = sessions.get(session.opaqueReference());
    boolean sameProcessSession = state != null && state.runtimeSessionId != null;
    return CompletableFuture.completedFuture(
        new SessionInspection(
            sameProcessSession,
            sameProcessSession,
            false,
            sameProcessSession
                ? "WS Session is known only inside the current Node process"
                : "WS Session identity cannot be proven"));
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
    runtime.cancel();
    runtimeExecutor.shutdownNow();
  }

  private SessionState requireSession(SessionHandle session) {
    SessionState state = sessions.get(session.opaqueReference());
    if (state == null) {
      throw new IllegalStateException("Unknown WS Session handle");
    }
    return state;
  }

  private static CompletionStage<Void> unsupported(String message) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException(message));
  }

  private static final class SessionState {
    private final SessionContext context;
    private String runtimeSessionId;

    private SessionState(SessionContext context) {
      this.context = context;
    }
  }
}
