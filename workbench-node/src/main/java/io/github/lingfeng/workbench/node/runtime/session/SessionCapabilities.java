package io.github.lingfeng.workbench.node.runtime.session;

import java.util.Set;

public record SessionCapabilities(String runtimeKind, Set<String> values) {

    public SessionCapabilities {
        values = Set.copyOf(values);
    }

    public boolean supports(String capability) {
        return values.contains(capability);
    }
}
