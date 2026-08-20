package io.github.lingfeng.workbench.service.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NodeRegistryRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public NodeRegistryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public void upsertNode(
      String nodeId, String displayName, List<String> capabilities, Instant observedAt) {
    jdbc.update(
        """
        INSERT INTO nodes(node_id, display_name, capabilities_json, last_heartbeat_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(node_id) DO UPDATE SET display_name=excluded.display_name,
        capabilities_json=excluded.capabilities_json, last_heartbeat_at=excluded.last_heartbeat_at,
        updated_at=excluded.updated_at
        """,
        nodeId,
        displayName,
        writeCapabilities(capabilities),
        observedAt.toString(),
        observedAt.toString(),
        observedAt.toString());
  }

  public boolean touchHeartbeat(String nodeId, Instant observedAt) {
    return jdbc.update(
            "UPDATE nodes SET last_heartbeat_at=?, updated_at=? WHERE node_id=?",
            observedAt.toString(),
            observedAt.toString(),
            nodeId)
        == 1;
  }

  public boolean exists(String nodeId) {
    Integer count =
        jdbc.queryForObject("SELECT count(*) FROM nodes WHERE node_id=?", Integer.class, nodeId);
    return count != null && count == 1;
  }

  private String writeCapabilities(List<String> capabilities) {
    try {
      return objectMapper.writeValueAsString(capabilities);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Node capabilities cannot be serialized", exception);
    }
  }
}
