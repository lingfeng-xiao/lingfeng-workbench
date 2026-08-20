package io.github.lingfeng.workbench.service.security;

import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class CredentialAuthenticator {
  private final String hermesToken;
  private final String sitesToken;
  private final String creatorToken;
  private final Map<String, String> nodeTokens;

  CredentialAuthenticator(WorkbenchProperties properties) {
    hermesToken = properties.security().hermesToken();
    sitesToken = properties.security().sitesToken();
    creatorToken = properties.security().creatorToken();
    nodeTokens = properties.security().nodeTokens();
    validateCredentials();
  }

  Optional<WorkbenchPrincipal> authenticate(String presentedToken) {
    if (constantTimeEquals(creatorToken, presentedToken)) {
      return Optional.of(new WorkbenchPrincipal(WorkbenchPrincipal.Kind.CREATOR, null));
    }
    if (constantTimeEquals(hermesToken, presentedToken)) {
      return Optional.of(new WorkbenchPrincipal(WorkbenchPrincipal.Kind.HERMES, null));
    }
    if (constantTimeEquals(sitesToken, presentedToken)) {
      return Optional.of(new WorkbenchPrincipal(WorkbenchPrincipal.Kind.SITES, null));
    }
    return nodeTokens.entrySet().stream()
        .filter(entry -> constantTimeEquals(entry.getValue(), presentedToken))
        .findFirst()
        .map(entry -> new WorkbenchPrincipal(WorkbenchPrincipal.Kind.NODE, entry.getKey()));
  }

  private void validateCredentials() {
    Set<String> uniqueTokens = new HashSet<>();
    requireUniqueNonBlank("hermes", hermesToken, uniqueTokens);
    requireUniqueNonBlank("sites", sitesToken, uniqueTokens);
    if (!creatorToken.isBlank()) {
      requireUniqueNonBlank("creator", creatorToken, uniqueTokens);
    }
    nodeTokens.forEach(
        (nodeId, token) -> {
          if (nodeId == null || !nodeId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
            throw new IllegalStateException("Node credential key is not a valid nodeId");
          }
          requireUniqueNonBlank("node " + nodeId, token, uniqueTokens);
        });
  }

  private void requireUniqueNonBlank(String owner, String token, Set<String> uniqueTokens) {
    if (token == null || token.length() < 32) {
      throw new IllegalStateException(owner + " credential must contain at least 32 characters");
    }
    if (!uniqueTokens.add(token)) {
      throw new IllegalStateException("Credentials must be unique across scopes");
    }
  }

  private boolean constantTimeEquals(String expected, String presented) {
    if (expected == null || presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }
}
