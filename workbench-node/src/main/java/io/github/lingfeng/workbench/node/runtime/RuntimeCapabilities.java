package io.github.lingfeng.workbench.node.runtime;

import java.util.Set;

public record RuntimeCapabilities(String runtimeKind, Set<String> capabilities) {

    public RuntimeCapabilities {
        capabilities = Set.copyOf(capabilities);
    }
}
