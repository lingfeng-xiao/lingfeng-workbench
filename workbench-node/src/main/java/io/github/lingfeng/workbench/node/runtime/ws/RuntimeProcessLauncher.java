package io.github.lingfeng.workbench.node.runtime.ws;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
interface RuntimeProcessLauncher {
  Process start(List<String> command, Path workingDirectory) throws IOException;
}
