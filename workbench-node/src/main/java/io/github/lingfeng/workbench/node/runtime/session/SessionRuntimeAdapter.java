package io.github.lingfeng.workbench.node.runtime.session;

import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface SessionRuntimeAdapter {

    CompletionStage<RuntimeProbe> probe();

    SessionCapabilities capabilities();

    CompletionStage<SessionHandle> openSession(
            SessionContext context, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> submitMission(
            SessionHandle session, MissionInput mission, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> provideInteractionResponse(
            SessionHandle session,
            InteractionInput response,
            Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<SessionInspection> reattach(
            SessionHandle session,
            SessionContext context,
            Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> cancel(
            SessionHandle session, String reasonSummary, Consumer<NormalizedRuntimeEvent> eventSink);

    CompletionStage<Void> closeSession(
            SessionHandle session, Consumer<NormalizedRuntimeEvent> eventSink);
}
