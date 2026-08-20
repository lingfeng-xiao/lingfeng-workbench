package io.github.lingfeng.workbench.node.runtime.session;

import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface SessionRuntimeAdapter {

    CompletionStage<RuntimeProbe> probe();

    SessionCapabilities capabilities();

    CompletionStage<SessionHandle> openSession(
            SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> submitTurn(
            SessionHandle session, TurnInput turn, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> provideInteractionResponse(
            SessionHandle session,
            InteractionInput response,
            Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> requestCheckpoint(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> pause(SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> resume(
            SessionHandle session, String checkpointId, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> cancel(
            SessionHandle session, String reasonSummary, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<SessionInspection> inspect(SessionHandle session);

    CompletionStage<Void> closeSession(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink);
}
