package io.github.lingfeng.workbench.node.runtime;

import io.github.lingfeng.workbench.node.protocol.Assignment;
import java.nio.file.Path;

public record RuntimeResumeContext(
        Assignment assignment,
        String runtimeSessionRef,
        String interactionResponse,
        Path workspace,
        Path evidenceDirectory) {
}
