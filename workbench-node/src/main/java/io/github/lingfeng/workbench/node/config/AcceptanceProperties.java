package io.github.lingfeng.workbench.node.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("workbench.acceptance")
public record AcceptanceProperties(Map<String, Profile> profiles) {

    @ConstructorBinding
    public AcceptanceProperties {
        profiles = profiles == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(profiles));
    }

    public record Profile(
            List<String> command,
            Duration timeout,
            List<String> requiredArtifacts,
            Integer maxOutputBytes) {

        public Profile {
            command = command == null ? List.of() : List.copyOf(command);
            requiredArtifacts = requiredArtifacts == null ? List.of() : List.copyOf(requiredArtifacts);
        }
    }
}
