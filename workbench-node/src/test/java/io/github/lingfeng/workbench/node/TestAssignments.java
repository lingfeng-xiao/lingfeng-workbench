package io.github.lingfeng.workbench.node;

import io.github.lingfeng.workbench.node.protocol.Assignment;

public final class TestAssignments {

    public static final String DIGEST = "a".repeat(64);

    private TestAssignments() {
    }

    public static Assignment assignment() {
        return new Assignment(
                "command-1",
                "work-item-1",
                "mission-1",
                "run-1",
                1,
                DIGEST,
                "Produce a short local answer",
                "Return the expected phrase",
                "No side effects",
                "office-pc",
                "sandbox",
                "ws",
                "no-tools");
    }
}
