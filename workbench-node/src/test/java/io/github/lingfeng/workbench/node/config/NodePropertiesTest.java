package io.github.lingfeng.workbench.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodePropertiesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesConfiguredWorkspaceWithoutClaimingSandboxing() {
        Path workspace = temporaryDirectory.resolve("workspace");
        NodeProperties properties = properties(URI.create("https://service.example/"), Map.of("sandbox", workspace));

        assertThat(properties.resolveWorkspace("sandbox")).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(properties.wsAgent()).isEqualTo("build");
        assertThat(properties.wsProviderId()).isEqualTo("workspace");
        assertThat(properties.wsModelId()).isEqualTo("gpt-5.6-luna");
    }

    @Test
    void rejectsNonHttpsServiceEndpoint() {
        assertThatThrownBy(() -> properties(URI.create("http://service.example/"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsUnknownWorkspaceReference() {
        NodeProperties properties = properties(URI.create("https://service.example/"), Map.of());

        assertThatThrownBy(() -> properties.resolveWorkspace("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void rejectsMissingWsPromptTarget() {
        assertThatThrownBy(() -> new NodeProperties(
                "office-pc", "Office PC", URI.create("https://service.example/"), "x".repeat(32),
                temporaryDirectory.resolve("state"), Duration.ofSeconds(5), Duration.ofSeconds(20),
                "ws", URI.create("http://127.0.0.1:4096/"), "0.0.0--test",
                "build", "workspace", "", Duration.ofSeconds(2), Duration.ofSeconds(1), Map.of(),
                Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30),
                null, null, null, null, "FLOW", Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wsModelId");
    }

    private NodeProperties properties(URI serviceUri, Map<String, Path> workspaces) {
        return new NodeProperties(
                "office-pc",
                "Office PC",
                serviceUri,
                "x".repeat(32),
                temporaryDirectory.resolve("state"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                "ws",
                URI.create("http://127.0.0.1:4096/"),
                "0.0.0--test",
                workspaces);
    }
}
