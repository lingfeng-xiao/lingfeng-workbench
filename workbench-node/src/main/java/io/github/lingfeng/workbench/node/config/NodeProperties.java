package io.github.lingfeng.workbench.node.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Map<String, Path> workspaces,
        Duration heartbeatInterval,
        Duration connectTimeout,
        Duration backoffInitial,
        Duration backoffMaximum,
        URI proxyUri,
        Path proxyPasswordFile,
        Path trustStore,
        Path trustStorePasswordFile,
        String fakeScenario,
        Duration fakeTurnDelay) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    @ConstructorBinding
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
        heartbeatInterval = positiveOrDefault(heartbeatInterval, Duration.ofSeconds(15), "heartbeatInterval");
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(10), "connectTimeout");
        backoffInitial = positiveOrDefault(backoffInitial, Duration.ofSeconds(1), "backoffInitial");
        backoffMaximum = positiveOrDefault(backoffMaximum, Duration.ofSeconds(30), "backoffMaximum");
        if (backoffMaximum.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException("backoffMaximum must not be shorter than backoffInitial");
        }
        if (proxyUri != null && !("http".equalsIgnoreCase(proxyUri.getScheme())
                || "https".equalsIgnoreCase(proxyUri.getScheme()))) {
            throw new IllegalArgumentException("proxyUri must use HTTP or HTTPS");
        }
        requireIdentifier(runtimeKind, "runtimeKind");
        if (wsExecutable == null || wsExecutable.isBlank()) {
            throw new IllegalArgumentException("wsExecutable is required");
        }
        workspaces = workspaces == null ? Map.of() : Map.copyOf(workspaces);
        workspaces.keySet().forEach(key -> requireIdentifier(key, "workspaceRef"));
        fakeScenario = fakeScenario == null || fakeScenario.isBlank() ? "FLOW" : fakeScenario.toUpperCase();
        if (!fakeScenario.equals("FLOW") && !fakeScenario.equals("INTERACTION")) {
            throw new IllegalArgumentException("fakeScenario must be FLOW or INTERACTION");
        }
        fakeTurnDelay = positiveOrDefault(fakeTurnDelay, Duration.ofMillis(100), "fakeTurnDelay");
    }

    public NodeProperties(
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
        this(nodeId, displayName, serviceBaseUri, bearerToken, stateDirectory, pollInterval, requestTimeout,
                runtimeKind, wsExecutable, workspaces, Duration.ofSeconds(15), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), null, null, null, null, "FLOW",
                Duration.ofMillis(100));
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

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String fieldName) {
        Duration selected = value == null ? defaultValue : value;
        if (selected.isNegative() || selected.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return selected;
    }
}
