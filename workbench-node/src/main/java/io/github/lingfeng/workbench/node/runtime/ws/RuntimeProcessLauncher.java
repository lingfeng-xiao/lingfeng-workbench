package io.github.lingfeng.workbench.node.runtime.ws;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
interface RuntimeProcessLauncher {

    Process start(List<String> command) throws IOException;
}
