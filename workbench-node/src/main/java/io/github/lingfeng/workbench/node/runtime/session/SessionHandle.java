package io.github.lingfeng.workbench.node.runtime.session;

public record SessionHandle(String opaqueReference) {

    public SessionHandle {
        if (opaqueReference == null || opaqueReference.isBlank()) {
            throw new IllegalArgumentException("Session handle is required");
        }
    }
}
