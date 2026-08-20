package io.github.lingfeng.workbench.service.application;

import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import io.github.lingfeng.workbench.service.persistence.V2Repository;
import io.github.lingfeng.workbench.service.persistence.V2Repository.Notification;
import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeOfflineNotificationService {
  private final V2Repository repository;
  private final WorkbenchProperties properties;
  private final V2ProtocolSupport protocolSupport;
  private final Clock clock = Clock.systemUTC();

  public NodeOfflineNotificationService(
      V2Repository repository,
      WorkbenchProperties properties,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.repository = repository;
    this.properties = properties;
    this.protocolSupport = new V2ProtocolSupport(objectMapper);
  }

  @Scheduled(fixedDelayString = "${workbench.node.offline-scan-interval:30s}")
  @Transactional
  public void createMissingOfflineNotifications() {
    Instant now = clock.instant();
    Instant offlineBefore = now.minus(properties.node().offlineAfter());
    repository
        .findOfflineActiveRuns(offlineBefore)
        .forEach(
            binding ->
                repository.insertNotification(
                    new Notification(
                        protocolSupport.id("ntf_"),
                        binding.nodeId()
                            + ":"
                            + binding.runId()
                            + ":NODE_OFFLINE_WITH_ACTIVE_RUN:owner",
                        "NODE_OFFLINE_WITH_ACTIVE_RUN",
                        binding.workItemId(),
                        binding.missionId(),
                        binding.runId(),
                        null,
                        "Node offline with active Run",
                        "Node heartbeat is overdue while the Run remains active",
                        now)));
  }
}
