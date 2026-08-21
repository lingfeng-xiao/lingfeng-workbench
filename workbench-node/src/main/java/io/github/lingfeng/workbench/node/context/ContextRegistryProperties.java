package io.github.lingfeng.workbench.node.context;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("workbench.context-registry")
public record ContextRegistryProperties(Map<String, Path> entries, List<Path> allowedRoots) {
  @ConstructorBinding
  public ContextRegistryProperties {
    entries = entries == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(entries));
    allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
  }
}
