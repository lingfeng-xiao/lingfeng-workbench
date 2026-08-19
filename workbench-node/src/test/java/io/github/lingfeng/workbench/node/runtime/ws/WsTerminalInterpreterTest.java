package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.TestAssignments.DIGEST;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import org.junit.jupiter.api.Test;

class WsTerminalInterpreterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsPassedTerminalOnlyForExpectedDigestAndSuccessfulRuntime() throws Exception {
        RuntimeEvent event = WsTerminalInterpreter.interpret(objectMapper.readTree("""
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "SUCCEEDED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "accepted"
                }
                """.formatted(DIGEST)), DIGEST);

        assertThat(event).isEqualTo(new RuntimeEvent.Finished(
                RuntimeEvent.RuntimeOutcome.SUCCEEDED,
                RuntimeEvent.AcceptanceStatus.PASSED,
                "accepted"));
    }

    @Test
    void rejectsTerminalForDifferentMissionDigest() throws Exception {
        RuntimeEvent event = WsTerminalInterpreter.interpret(objectMapper.readTree("""
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "SUCCEEDED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "accepted"
                }
                """.formatted("b".repeat(64))), DIGEST);

        assertThat(event).isEqualTo(new RuntimeEvent.Failed(
                "Runtime terminal mission digest did not match",
                RuntimeEvent.AcceptanceStatus.UNKNOWN));
    }

    @Test
    void downgradesImpossiblePassedFailureToUnknown() throws Exception {
        RuntimeEvent event = WsTerminalInterpreter.interpret(objectMapper.readTree("""
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "FAILED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "contradiction"
                }
                """.formatted(DIGEST)), DIGEST);

        assertThat(event).isEqualTo(new RuntimeEvent.Failed(
                "Runtime claimed acceptance without successful execution",
                RuntimeEvent.AcceptanceStatus.UNKNOWN));
    }
}
