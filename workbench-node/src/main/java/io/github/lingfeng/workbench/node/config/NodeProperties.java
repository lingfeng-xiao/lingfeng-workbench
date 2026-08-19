package io.github.lingfeng.workbench.node.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workbench.node")
public record NodeProperties(
        String nodeId,
        String displayName,
        URI serviceBaseUri,
        String bearerToken,
        Path stateDirectory,
        Duration pollInterval,
        Duration requestTimeout,
        String runtimeKind,
        String wsExecutable,
        Map<String, Path> workspaces) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public NodeProperties {
        requireIdentifier(nodeId, "nodeId");
        if (displayName == null || displayName.isBlank() || displayName.length() > 800) {
            throw new IllegalArgumentException("displayName must contain 1 to 800 characters");
        }
        if (serviceBaseUri == null || !"https".equalsIgnoreCase(serviceBaseUri.getScheme())) {
            throw new IllegalArgumentException("serviceBaseUri must use HTTPS");
        }
        if (bearerToken == null || bearerToken.length() < 32) {
            throw new IllegalArgumentException("bearerToken must contain at least 32 characters");
        }
        if (stateDirectory == null) {
            throw new IllegalArgumentException("stateDirectory is required");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        requireIdentifier(runtimeKind, "runtimeKind");
        if (wsExecutable == null || wsExecutable.isBlank()) {
            throw new IllegalArgumentException("wsExecutable is required");
        }
        workspaces = workspaces == null ? Map.of() : Map.copyOf(workspaces);
        workspaces.keySet().forEach(key -> requireIdentifier(key, "workspaceRef"));
    }

    public Path resolveWorkspace(String workspaceRef) {
        requireIdentifier(workspaceRef, "workspaceRef");
        Path workspace = workspaces.get(workspaceRef);
        if (workspace == null) {
            throw new IllegalArgumentException("workspaceRef is not configured: " + workspaceRef);
        }
        return workspace.toAbsolutePath().normalize();
    }

    private static void requireIdentifier(String value, String fieldName) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a protocol identifier");
        }
    }
}
