package io.github.lingfeng.workbench.node.runtime;

public sealed interface RuntimeEvent permits RuntimeEvent.Started, RuntimeEvent.Progress,
        RuntimeEvent.InteractionRequested, RuntimeEvent.Finished, RuntimeEvent.Failed, RuntimeEvent.Interrupted {

    record Started(boolean resumable, String runtimeSessionRef) implements RuntimeEvent {
    }

    record Progress(String summary) implements RuntimeEvent {
    }

    record InteractionRequested(String checkpointId, String promptSummary) implements RuntimeEvent {
    }

    record Finished(RuntimeOutcome runtimeOutcome, AcceptanceStatus acceptanceStatus, String resultSummary)
            implements RuntimeEvent {
    }

    record Failed(String summary, AcceptanceStatus acceptanceStatus) implements RuntimeEvent {
    }

    record Interrupted(String summary) implements RuntimeEvent {
    }

    enum RuntimeOutcome {
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }

    enum AcceptanceStatus {
        PASSED,
        FAILED,
        UNKNOWN
    }
}
