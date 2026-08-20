package io.github.lingfeng.workbench.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class V2ProtocolSupport {
  private final ObjectMapper objectMapper;

  V2ProtocolSupport(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Protocol value cannot be serialized", exception);
    }
  }

  <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Durable protocol value is invalid", exception);
    }
  }

  String hash(Object value) {
    return hashBytes(json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  String hashJson(String json) {
    return hashBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String hashBytes(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }
}
