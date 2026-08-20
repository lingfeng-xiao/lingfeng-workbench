package io.github.lingfeng.workbench.node.runtime.session;

import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import java.nio.file.Path;

public record SessionContext(NodeCommand.StartRun command, Path workspace, Path evidenceDirectory) {
}
