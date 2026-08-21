package io.github.lingfeng.workbench.node.runtime.session;

import java.util.Set;

public sealed interface NormalizedRuntimeEvent permits NormalizedRuntimeEvent.SessionOpened,
        NormalizedRuntimeEvent.MissionAccepted, NormalizedRuntimeEvent.StatusChanged,
        NormalizedRuntimeEvent.PhaseChanged, NormalizedRuntimeEvent.ProgressUpdated,
        NormalizedRuntimeEvent.InteractionRequested, NormalizedRuntimeEvent.RuntimeIdle,
        NormalizedRuntimeEvent.SessionFailed, NormalizedRuntimeEvent.SessionClosed {

    record SessionOpened(boolean resumable) implements NormalizedRuntimeEvent {
    }

    record MissionAccepted() implements NormalizedRuntimeEvent {
    }

    record StatusChanged(RuntimeStatus status, String summary) implements NormalizedRuntimeEvent {
    }

    record PhaseChanged(String phaseCode, String summary) implements NormalizedRuntimeEvent {
    }

    record ProgressUpdated(String summary) implements NormalizedRuntimeEvent {
    }

    record InteractionRequested(
            String interactionId,
            String checkpointId,
            String promptSummary,
            Set<String> allowedDecisions,
            boolean resumable) implements NormalizedRuntimeEvent {

        public InteractionRequested {
            allowedDecisions = Set.copyOf(allowedDecisions);
        }
    }

    record RuntimeIdle(String resultSummary) implements NormalizedRuntimeEvent {
    }

    record SessionFailed(String summary) implements NormalizedRuntimeEvent {
    }

    record SessionClosed() implements NormalizedRuntimeEvent {
    }

    enum RuntimeOutcome {
        SUCCEEDED,
        FAILED,
        INTERRUPTED,
        UNKNOWN
    }

    enum AcceptanceStatus {
        PASSED,
        FAILED,
        UNKNOWN
    }
}
