package io.github.lingfeng.workbench.node.runtime.session;

public record SessionHandle(
        String opaqueReference,
        String runtimeIdentity,
        String runtimeVersion,
        String workspaceDirectory) {

    public SessionHandle {
        if (opaqueReference == null || opaqueReference.isBlank()) {
            throw new IllegalArgumentException("Session handle is required");
        }
        if (runtimeIdentity == null || runtimeIdentity.isBlank()) {
            throw new IllegalArgumentException("Runtime identity is required");
        }
        if (runtimeVersion == null || runtimeVersion.isBlank()) {
            throw new IllegalArgumentException("Runtime version is required");
        }
        if (workspaceDirectory == null || workspaceDirectory.isBlank()) {
            throw new IllegalArgumentException("Workspace directory is required");
        }
    }
}
