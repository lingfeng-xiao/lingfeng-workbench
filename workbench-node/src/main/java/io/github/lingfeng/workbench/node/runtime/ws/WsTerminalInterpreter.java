package io.github.lingfeng.workbench.node.runtime.ws;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import java.util.Locale;

final class WsTerminalInterpreter {

  private WsTerminalInterpreter() {}

  static NormalizedRuntimeEvent.Terminal interpret(JsonNode event, String expectedMissionDigest) {
    if (!"lingfeng.terminal".equals(event.path("type").asText())) {
      return null;
    }
    String actualDigest = event.path("missionDigest").asText();
    if (!expectedMissionDigest.equals(actualDigest)) {
      return unknown(actualDigest, "Runtime terminal mission digest did not match");
    }
    NormalizedRuntimeEvent.RuntimeOutcome runtimeOutcome =
        parseRuntimeOutcome(event.path("runtimeOutcome").asText());
    NormalizedRuntimeEvent.AcceptanceStatus acceptanceStatus =
        parseAcceptanceStatus(event.path("acceptanceStatus").asText());
    String summary =
        compactSummary(event.path("resultSummary").asText(), "Runtime returned no result summary");
    if (runtimeOutcome == null || acceptanceStatus == null) {
      return unknown(expectedMissionDigest, "Runtime terminal contains unsupported status");
    }
    if (acceptanceStatus == NormalizedRuntimeEvent.AcceptanceStatus.PASSED
        && runtimeOutcome != NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED) {
      return unknown(
          expectedMissionDigest, "Runtime claimed acceptance without successful execution");
    }
    return new NormalizedRuntimeEvent.Terminal(
        expectedMissionDigest, runtimeOutcome, acceptanceStatus, summary);
  }

  static String compactSummary(String source, String fallback) {
    String compact = source == null ? "" : source.trim().replaceAll("\\s+", " ");
    if (compact.isEmpty()) {
      compact = fallback;
    }
    return compact.length() <= 800 ? compact : compact.substring(0, 797) + "...";
  }

  private static NormalizedRuntimeEvent.Terminal unknown(String digest, String summary) {
    return new NormalizedRuntimeEvent.Terminal(
        digest,
        NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
        NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
        summary);
  }

  private static NormalizedRuntimeEvent.RuntimeOutcome parseRuntimeOutcome(String value) {
    try {
      return NormalizedRuntimeEvent.RuntimeOutcome.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static NormalizedRuntimeEvent.AcceptanceStatus parseAcceptanceStatus(String value) {
    try {
      return NormalizedRuntimeEvent.AcceptanceStatus.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
