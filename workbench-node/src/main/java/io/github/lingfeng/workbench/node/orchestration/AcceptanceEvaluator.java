package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.util.concurrent.CompletionStage;

public interface AcceptanceEvaluator {

    CompletionStage<AcceptanceResult> evaluate(
            SessionContext context, SessionHandle session, String runtimeSummary);
}
