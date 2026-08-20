package io.github.lingfeng.workbench.node.runtime.session;

import java.util.Set;

public sealed interface NormalizedRuntimeEvent permits NormalizedRuntimeEvent.SessionOpened,
        NormalizedRuntimeEvent.TurnAccepted, NormalizedRuntimeEvent.PhaseChanged,
        NormalizedRuntimeEvent.ProgressUpdated, NormalizedRuntimeEvent.InteractionRequested,
        NormalizedRuntimeEvent.CheckpointSaved, NormalizedRuntimeEvent.Paused,
        NormalizedRuntimeEvent.Resumed, NormalizedRuntimeEvent.TurnFinished,
        NormalizedRuntimeEvent.SessionFailed, NormalizedRuntimeEvent.SessionClosed,
        NormalizedRuntimeEvent.Terminal {

    record SessionOpened(boolean resumable) implements NormalizedRuntimeEvent {
    }

    record TurnAccepted(String turnId) implements NormalizedRuntimeEvent {
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

    record CheckpointSaved(String checkpointId) implements NormalizedRuntimeEvent {
    }

    record Paused(String checkpointId) implements NormalizedRuntimeEvent {
    }

    record Resumed(String checkpointId) implements NormalizedRuntimeEvent {
    }

    record TurnFinished(String turnId) implements NormalizedRuntimeEvent {
    }

    record SessionFailed(String summary) implements NormalizedRuntimeEvent {
    }

    record SessionClosed() implements NormalizedRuntimeEvent {
    }

    record Terminal(
            String missionDigest,
            RuntimeOutcome runtimeOutcome,
            AcceptanceStatus acceptanceStatus,
            String resultSummary) implements NormalizedRuntimeEvent {
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
