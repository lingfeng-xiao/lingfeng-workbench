package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;

public record AcceptanceResult(
        NormalizedRuntimeEvent.AcceptanceStatus status,
        String summary) {

    public AcceptanceResult {
        if (status == null) {
            throw new IllegalArgumentException("Acceptance status is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Acceptance summary is required");
        }
    }
}
