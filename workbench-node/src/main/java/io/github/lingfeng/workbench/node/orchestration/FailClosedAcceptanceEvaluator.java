package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FailClosedAcceptanceEvaluator implements AcceptanceEvaluator {

    @Override
    public CompletionStage<AcceptanceResult> evaluate(
            SessionContext context, SessionHandle session, String runtimeSummary) {
        return CompletableFuture.completedFuture(new AcceptanceResult(
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "OpenCode became idle, but no independent acceptance evaluator is configured"));
    }
}
