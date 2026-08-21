package io.github.lingfeng.workbench.service.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workbench")
public record WorkbenchProperties(
    Security security, Node node, Notification notification, Task task) {

  public WorkbenchProperties {
    security = security == null ? new Security("", "", "", Map.of()) : security;
    node = node == null ? new Node(Duration.ofSeconds(90), Duration.ofSeconds(30)) : node;
    notification = notification == null ? new Notification(Duration.ofMinutes(5), 3) : notification;
    task = task == null ? new Task(Duration.ofSeconds(30)) : task;
  }

  public record Security(
      String hermesToken, String sitesToken, String creatorToken, Map<String, String> nodeTokens) {
    public Security {
      hermesToken = hermesToken == null ? "" : hermesToken;
      sitesToken = sitesToken == null ? "" : sitesToken;
      creatorToken = creatorToken == null ? "" : creatorToken;
      nodeTokens = nodeTokens == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodeTokens));
    }
  }

  public record Node(Duration offlineAfter, Duration offlineScanInterval) {
    public Node {
      offlineAfter = offlineAfter == null ? Duration.ofSeconds(90) : offlineAfter;
      offlineScanInterval =
          offlineScanInterval == null ? Duration.ofSeconds(30) : offlineScanInterval;
    }
  }

  public record Notification(Duration leaseDuration, int maxAttempts) {
    public Notification {
      leaseDuration = leaseDuration == null ? Duration.ofMinutes(5) : leaseDuration;
      if (maxAttempts < 1) {
        maxAttempts = 3;
      }
    }
  }

  public record Task(Duration observationStaleAfter) {
    public Task {
      observationStaleAfter =
          observationStaleAfter == null ? Duration.ofSeconds(30) : observationStaleAfter;
    }
  }
}
