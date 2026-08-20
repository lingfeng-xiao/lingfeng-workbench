package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.V2TestCommands.DIGEST;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import org.junit.jupiter.api.Test;

class WsTerminalInterpreterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void acceptsPassedTerminalOnlyForExpectedDigestAndSuccessfulRuntime() throws Exception {
    NormalizedRuntimeEvent.Terminal event =
        WsTerminalInterpreter.interpret(
            objectMapper.readTree(
                """
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "SUCCEEDED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "accepted"
                }
                """
                    .formatted(DIGEST)),
            DIGEST);

    assertThat(event)
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                "accepted"));
  }

  @Test
  void preservesDifferentDigestSoSupervisorCanFailClosed() throws Exception {
    String wrongDigest = "b".repeat(64);

    NormalizedRuntimeEvent.Terminal event =
        WsTerminalInterpreter.interpret(
            objectMapper.readTree(
                """
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "SUCCEEDED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "accepted"
                }
                """
                    .formatted(wrongDigest)),
            DIGEST);

    assertThat(event.missionDigest()).isEqualTo(wrongDigest);
    assertThat(event.runtimeOutcome()).isEqualTo(NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN);
    assertThat(event.acceptanceStatus())
        .isEqualTo(NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN);
  }

  @Test
  void downgradesImpossiblePassedFailureToUnknown() throws Exception {
    NormalizedRuntimeEvent.Terminal event =
        WsTerminalInterpreter.interpret(
            objectMapper.readTree(
                """
                {
                  "type": "lingfeng.terminal",
                  "missionDigest": "%s",
                  "runtimeOutcome": "FAILED",
                  "acceptanceStatus": "PASSED",
                  "resultSummary": "contradiction"
                }
                """
                    .formatted(DIGEST)),
            DIGEST);

    assertThat(event)
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "Runtime claimed acceptance without successful execution"));
  }
}
