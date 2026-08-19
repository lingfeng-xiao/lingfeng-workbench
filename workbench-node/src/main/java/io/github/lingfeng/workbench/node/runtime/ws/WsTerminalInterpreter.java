package io.github.lingfeng.workbench.node.runtime.ws;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import java.util.Locale;

final class WsTerminalInterpreter {

    private WsTerminalInterpreter() {
    }

    static RuntimeEvent interpret(JsonNode event, String expectedMissionDigest) {
        if (!"lingfeng.terminal".equals(event.path("type").asText())) {
            return null;
        }
        String actualDigest = event.path("missionDigest").asText();
        if (!expectedMissionDigest.equals(actualDigest)) {
            return new RuntimeEvent.Failed("Runtime terminal mission digest did not match", RuntimeEvent.AcceptanceStatus.UNKNOWN);
        }
        RuntimeEvent.RuntimeOutcome runtimeOutcome = parseRuntimeOutcome(event.path("runtimeOutcome").asText());
        RuntimeEvent.AcceptanceStatus acceptanceStatus = parseAcceptanceStatus(
                event.path("acceptanceStatus").asText());
        String summary = compactSummary(event.path("resultSummary").asText(), "Runtime returned no result summary");
        if (runtimeOutcome == null || acceptanceStatus == null) {
            return new RuntimeEvent.Failed("Runtime terminal contains unsupported status", RuntimeEvent.AcceptanceStatus.UNKNOWN);
        }
        if (acceptanceStatus == RuntimeEvent.AcceptanceStatus.PASSED
                && runtimeOutcome != RuntimeEvent.RuntimeOutcome.SUCCEEDED) {
            return new RuntimeEvent.Failed(
                    "Runtime claimed acceptance without successful execution",
                    RuntimeEvent.AcceptanceStatus.UNKNOWN);
        }
        return new RuntimeEvent.Finished(runtimeOutcome, acceptanceStatus, summary);
    }

    static String compactSummary(String source, String fallback) {
        String compact = source == null ? "" : source.trim().replaceAll("\\s+", " ");
        if (compact.isEmpty()) {
            compact = fallback;
        }
        return compact.length() <= 800 ? compact : compact.substring(0, 797) + "...";
    }

    private static RuntimeEvent.RuntimeOutcome parseRuntimeOutcome(String value) {
        try {
            return RuntimeEvent.RuntimeOutcome.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static RuntimeEvent.AcceptanceStatus parseAcceptanceStatus(String value) {
        try {
            return RuntimeEvent.AcceptanceStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
