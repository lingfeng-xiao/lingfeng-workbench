package io.github.lingfeng.workbench.node.context;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContextRegistry {
  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

  private final NodeProperties nodeProperties;
  private final ContextRegistryProperties registryProperties;

  public ContextRegistry(
      NodeProperties nodeProperties, ContextRegistryProperties registryProperties) {
    this.nodeProperties = nodeProperties;
    this.registryProperties = registryProperties;
    registryProperties.entries().keySet().forEach(ref -> requireIdentifier(ref, "contextRef"));
  }

  public ResolvedContext resolve(String workspaceRef, List<String> contextRefs) {
    Path workspace = realPath(nodeProperties.resolveWorkspace(workspaceRef), "workspaceRef");
    if (!Files.isDirectory(workspace) || !Files.isReadable(workspace) || !Files.isWritable(workspace)) {
      throw new IllegalArgumentException("workspaceRef is not a readable and writable directory");
    }
    List<Path> allowedRoots = allowedRoots(workspace);
    List<String> requestedRefs = contextRefs == null ? List.of() : List.copyOf(contextRefs);
    if (requestedRefs.size() > 16 || new HashSet<>(requestedRefs).size() != requestedRefs.size()) {
      throw new IllegalArgumentException("contextRefs must contain at most 16 unique aliases");
    }
    List<Path> resolvedPaths = new ArrayList<>();
    for (String contextRef : requestedRefs) {
      requireIdentifier(contextRef, "contextRef");
      Path configuredPath = registryProperties.entries().get(contextRef);
      if (configuredPath == null) {
        throw new IllegalArgumentException("contextRef is not configured: " + contextRef);
      }
      Path resolvedPath = realPath(configuredPath, "contextRef");
      if (!Files.isReadable(resolvedPath)) {
        throw new IllegalArgumentException("contextRef is not readable: " + contextRef);
      }
      if (allowedRoots.stream().noneMatch(resolvedPath::startsWith)) {
        throw new IllegalArgumentException("contextRef resolves outside the allowed roots: " + contextRef);
      }
      resolvedPaths.add(resolvedPath);
    }
    return new ResolvedContext(workspace, List.copyOf(resolvedPaths));
  }

  private List<Path> allowedRoots(Path workspace) {
    List<Path> configuredRoots = registryProperties.allowedRoots();
    if (configuredRoots.isEmpty()) {
      return List.of(workspace);
    }
    return configuredRoots.stream().map(root -> realPath(root, "allowedRoot")).toList();
  }

  private Path realPath(Path path, String fieldName) {
    try {
      return path.toRealPath();
    } catch (IOException exception) {
      throw new IllegalArgumentException(fieldName + " does not resolve to an existing path", exception);
    }
  }

  private void requireIdentifier(String value, String fieldName) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(fieldName + " is not a safe alias");
    }
  }

  public record ResolvedContext(Path workspace, List<Path> contextPaths) {}
}
