package io.github.lingfeng.workbench.node.orchestration;

import io.github.lingfeng.workbench.node.config.AcceptanceProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AcceptanceProfileRegistry {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(30);
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;

    private final Map<String, AcceptanceProfile> profiles;

    public AcceptanceProfileRegistry(AcceptanceProperties properties) {
        Map<String, AcceptanceProfile> validated = new LinkedHashMap<>();
        properties.profiles().forEach((profileId, configured) ->
                validated.put(profileId, validate(profileId, configured)));
        profiles = Map.copyOf(validated);
    }

    public Optional<AcceptanceProfile> find(String profileId) {
        return Optional.ofNullable(profiles.get(profileId));
    }

    private AcceptanceProfile validate(String profileId, AcceptanceProperties.Profile configured) {
        requireIdentifier(profileId);
        List<String> command = configured.command();
        int commandCharacters = command.stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        if (command.isEmpty() || command.size() > 32 || commandCharacters > 16_384
                || command.stream().anyMatch(argument -> argument == null
                || argument.isBlank() || argument.length() > 8192)) {
            throw new IllegalArgumentException(
                    "Acceptance profile command must contain 1 to 32 bounded arguments: " + profileId);
        }
        Duration timeout = configured.timeout() == null ? DEFAULT_TIMEOUT : configured.timeout();
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Acceptance profile timeout must be positive and at most 30 minutes: " + profileId);
        }
        List<String> requiredArtifacts = configured.requiredArtifacts();
        if (requiredArtifacts.size() > 32) {
            throw new IllegalArgumentException(
                    "Acceptance profile may require at most 32 artifacts: " + profileId);
        }
        requiredArtifacts.forEach(artifact -> requireSafeRelativePath(profileId, artifact));
        int maxOutputBytes = configured.maxOutputBytes() == null
                ? DEFAULT_MAX_OUTPUT_BYTES : configured.maxOutputBytes();
        if (maxOutputBytes < 1024 || maxOutputBytes > MAXIMUM_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Acceptance profile maxOutputBytes must be between 1024 and 1048576: " + profileId);
        }
        return new AcceptanceProfile(profileId, command, timeout, requiredArtifacts, maxOutputBytes);
    }

    private static void requireIdentifier(String profileId) {
        if (profileId == null || !IDENTIFIER.matcher(profileId).matches()) {
            throw new IllegalArgumentException("Acceptance profile ID is not a safe identifier");
        }
    }

    private static void requireSafeRelativePath(String profileId, String artifact) {
        if (artifact == null || artifact.isBlank() || artifact.length() > 512) {
            throw new IllegalArgumentException(
                    "Acceptance profile artifact path is invalid: " + profileId);
        }
        Path path = Path.of(artifact);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException(
                    "Acceptance profile artifact must stay inside the workspace: " + profileId);
        }
    }

    public record AcceptanceProfile(
            String profileId,
            List<String> command,
            Duration timeout,
            List<String> requiredArtifacts,
            int maxOutputBytes) {

        public AcceptanceProfile {
            command = List.copyOf(command);
            requiredArtifacts = List.copyOf(requiredArtifacts);
        }
    }
}
