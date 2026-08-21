package io.github.lingfeng.workbench.node.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextRegistryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resolvesSafeAliasesInsideAllowedRoots() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path context = Files.writeString(workspace.resolve("design.md"), "frozen design");
    ContextRegistry registry =
        new ContextRegistry(
            properties(workspace),
            new ContextRegistryProperties(
                Map.of("context-design", context), List.of(workspace)));

    ContextRegistry.ResolvedContext resolved =
        registry.resolve("workspace-main", List.of("context-design"));

    assertThat(resolved.workspace()).isEqualTo(workspace.toRealPath());
    assertThat(resolved.contextPaths()).containsExactly(context.toRealPath());
  }

  @Test
  void failsClosedBeforeRuntimeForUnknownMissingAndOutsideAliases() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path outside = Files.writeString(temporaryDirectory.resolve("outside.md"), "not allowed");
    ContextRegistry registry =
        new ContextRegistry(
            properties(workspace),
            new ContextRegistryProperties(
                Map.of(
                    "context-outside", outside,
                    "context-missing", workspace.resolve("missing.md")),
                List.of(workspace)));

    assertThatThrownBy(() -> registry.resolve("workspace-main", List.of("context-unknown")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> registry.resolve("workspace-main", List.of("context-missing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not resolve");
    assertThatThrownBy(() -> registry.resolve("workspace-main", List.of("context-outside")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside the allowed roots");
  }

  private NodeProperties properties(Path workspace) {
    return new NodeProperties(
        "node-alpha",
        "Alpha",
        URI.create("https://service.example.test/"),
        "node-token-000000000000000000000000000000",
        temporaryDirectory.resolve("state"),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        "fake-session",
        null,
        null,
        Map.of("workspace-main", workspace));
  }
}
