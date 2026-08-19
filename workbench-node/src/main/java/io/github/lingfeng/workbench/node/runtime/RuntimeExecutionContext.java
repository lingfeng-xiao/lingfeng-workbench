package io.github.lingfeng.workbench.node.runtime;

import io.github.lingfeng.workbench.node.protocol.Assignment;
import java.nio.file.Path;

public record RuntimeExecutionContext(Assignment assignment, Path workspace, Path evidenceDirectory) {
}
