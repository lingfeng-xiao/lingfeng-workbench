package io.github.lingfeng.workbench.node.runtime.session;

import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import java.nio.file.Path;
import java.util.List;

public record SessionContext(
        NodeCommand.StartRun command,
        Path workspace,
        List<Path> contextPaths,
        Path evidenceDirectory) {

    public SessionContext {
        contextPaths = contextPaths == null ? List.of() : List.copyOf(contextPaths);
    }

    public SessionContext(NodeCommand.StartRun command, Path workspace, Path evidenceDirectory) {
        this(command, workspace, List.of(), evidenceDirectory);
    }
}
