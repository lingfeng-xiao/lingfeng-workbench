package io.github.lingfeng.workbench.service.domain;

public final class Statuses {
    private Statuses() {}

    public enum WorkItemStatus {
        open, in_progress, completed, attention_required, cancelled
    }

    public enum MissionStatus {
        pending, assigned, running, waiting_interaction, completed, failed, interrupted, uncertain, cancelled
    }

    public enum RunStatus {
        assigned, running, waiting_interaction, completed, failed, interrupted, uncertain, cancelled
    }

    public enum AcceptanceStatus {
        PASSED, FAILED, UNKNOWN
    }

    public enum RuntimeOutcome {
        SUCCEEDED, FAILED, INTERRUPTED
    }
}
