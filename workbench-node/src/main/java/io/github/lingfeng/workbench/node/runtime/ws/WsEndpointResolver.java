package io.github.lingfeng.workbench.node.runtime.ws;

import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodeClient;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodePromptTarget;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public final class WsEndpointResolver {

    private final URI baseUri;
    private final String expectedVersion;
    private final OpenCodeClient client;
    private final OpenCodePromptTarget promptTarget;

    public WsEndpointResolver(
            URI baseUri,
            String expectedVersion,
            OpenCodeClient client,
            OpenCodePromptTarget promptTarget) {
        this.baseUri = requireLoopback(baseUri);
        if (expectedVersion == null || expectedVersion.isBlank()) {
            throw new IllegalArgumentException("Expected WS version is required");
        }
        this.expectedVersion = expectedVersion;
        this.client = client;
        this.promptTarget = promptTarget;
    }

    public RuntimeProbe probe() {
        try {
            OpenCodeClient.Health health = client.health();
            if (!health.healthy()) {
                return new RuntimeProbe(false, "WS health endpoint reported unavailable");
            }
            if (!expectedVersion.equals(health.version())) {
                return new RuntimeProbe(false, "WS version mismatch");
            }
            client.sessionStatuses(null);
            client.permissions(null);
            client.questions(null);
            if (!client.supportsPromptTarget(promptTarget)) {
                return new RuntimeProbe(false, "WS configured agent/provider/model is unavailable");
            }
            return new RuntimeProbe(true, "WS native OpenCode API ready version=" + health.version());
        } catch (RuntimeException exception) {
            return new RuntimeProbe(false, "WS native OpenCode capability probe failed");
        }
    }

    public URI baseUri() {
        return baseUri;
    }

    public String expectedVersion() {
        return expectedVersion;
    }

    private static URI requireLoopback(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("WS base URI must be absolute HTTP(S)");
        }
        try {
            if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("WS base URI must resolve to loopback");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("WS base URI host cannot be resolved", exception);
        }
        return uri;
    }
}
