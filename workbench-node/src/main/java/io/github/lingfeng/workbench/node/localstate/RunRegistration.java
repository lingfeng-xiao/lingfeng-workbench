package io.github.lingfeng.workbench.node.localstate;

import java.nio.file.Path;

public record RunRegistration(Status status, Path evidenceDirectory) {

    public enum Status {
        NEW,
        EXISTING_ACTIVE,
        EXISTING_TERMINAL
    }
}
