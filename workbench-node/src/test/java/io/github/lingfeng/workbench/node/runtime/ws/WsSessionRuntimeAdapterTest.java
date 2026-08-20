package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.V2TestCommands.DIGEST;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.session.InteractionInput;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WsSessionRuntimeAdapterTest {

  @TempDir Path temporaryDirectory;

  @Test
  void continuesThreeTurnsInOneObservedWsSession() throws Exception {
    Queue<Process> processes = new ArrayDeque<>();
    processes.add(progressProcess("turn one"));
    processes.add(progressProcess("turn two"));
    processes.add(terminalProcess());
    List<List<String>> commands = new ArrayList<>();
    WsRuntimeAdapter runtime =
        new WsRuntimeAdapter(
            "ws",
            new ObjectMapper(),
            (command, directory) -> {
              commands.add(command);
              return processes.remove();
            });
    Path evidence = temporaryDirectory.resolve("evidence");
    Files.createDirectories(evidence);
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(runtime)) {
      SessionHandle handle =
          adapter
              .openSession(
                  new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidence),
                  events::add)
              .toCompletableFuture()
              .join();
      adapter
          .submitTurn(handle, new TurnInput("turn-1", 1, "first"), events::add)
          .toCompletableFuture()
          .join();
      adapter
          .submitTurn(handle, new TurnInput("turn-2", 2, "second"), events::add)
          .toCompletableFuture()
          .join();
      adapter
          .submitTurn(handle, new TurnInput("turn-3", 3, "final"), events::add)
          .toCompletableFuture()
          .join();

      assertThat(adapter.capabilities().supports("multi-turn")).isTrue();
      assertThat(adapter.capabilities().supports("resume")).isFalse();
      assertThat(adapter.inspect(handle).toCompletableFuture().join().sameSession()).isTrue();
      assertThat(adapter.inspect(handle).toCompletableFuture().join().resumable()).isFalse();
      assertThat(events)
          .contains(
              new NormalizedRuntimeEvent.TurnFinished("turn-1"),
              new NormalizedRuntimeEvent.TurnFinished("turn-2"),
              new NormalizedRuntimeEvent.TurnFinished("turn-3"),
              new NormalizedRuntimeEvent.Terminal(
                  DIGEST,
                  NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                  NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                  "three turns accepted"));
      assertThat(events)
          .containsSubsequence(
              new NormalizedRuntimeEvent.TurnFinished("turn-3"),
              new NormalizedRuntimeEvent.Terminal(
                  DIGEST,
                  NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                  NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                  "three turns accepted"));
      assertThat(commands.get(0)).doesNotContain("--session");
      assertThat(commands.get(1))
          .containsSubsequence("--session", "private-session");
      assertThat(commands.get(2))
          .containsSubsequence("--session", "private-session");
      assertThatThrownBy(
              () ->
                  adapter
                      .provideInteractionResponse(
                          handle,
                          new InteractionInput(
                              "cmd_1",
                              "int_001",
                              "cp_001",
                              NodeCommand.Decision.APPROVE,
                              "ok"),
                          event -> {})
                      .toCompletableFuture()
                      .join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void sessionDriftOverridesAnOtherwiseSuccessfulTerminal() throws Exception {
    Queue<Process> processes = new ArrayDeque<>();
    processes.add(progressProcess("turn one"));
    processes.add(progressProcess("turn two"));
    processes.add(terminalProcess("different-session"));
    WsRuntimeAdapter runtime =
        new WsRuntimeAdapter(
            "ws", new ObjectMapper(), (command, directory) -> processes.remove());
    Path evidence = temporaryDirectory.resolve("drift-evidence");
    Files.createDirectories(evidence);
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    try (WsSessionRuntimeAdapter adapter = new WsSessionRuntimeAdapter(runtime)) {
      SessionHandle handle =
          adapter
              .openSession(
                  new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidence),
                  events::add)
              .toCompletableFuture()
              .join();
      for (int turnNumber = 1; turnNumber <= 3; turnNumber++) {
        adapter
            .submitTurn(
                handle,
                new TurnInput("turn-" + turnNumber, turnNumber, "step"),
                events::add)
            .toCompletableFuture()
            .join();
      }

      NormalizedRuntimeEvent.Terminal failedClosed =
          new NormalizedRuntimeEvent.Terminal(
              DIGEST,
              NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
              NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
              "WS continued a different Agent Session");
      assertThat(events)
          .containsSubsequence(new NormalizedRuntimeEvent.TurnFinished("turn-3"), failedClosed)
          .doesNotContain(
              new NormalizedRuntimeEvent.Terminal(
                  DIGEST,
                  NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                  NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                  "three turns accepted"));
    }
  }

  private Process progressProcess(String summary) {
    return new WsRuntimeAdapterTest.StubProcess(
        "{\"sessionID\":\"private-session\",\"type\":\"session\"}\n"
            + "{\"type\":\"message\",\"part\":{\"type\":\"text\",\"text\":\""
            + summary
            + "\"}}\n",
        "",
        0);
  }

  private Process terminalProcess() throws Exception {
    return terminalProcess("private-session");
  }

  private Process terminalProcess(String sessionId) throws Exception {
    String terminal =
        new ObjectMapper()
            .writeValueAsString(
                "{\"type\":\"lingfeng.terminal\",\"missionDigest\":\""
                    + DIGEST
                    + "\",\"runtimeOutcome\":\"SUCCEEDED\",\"acceptanceStatus\":\"PASSED\",\"resultSummary\":\"three turns accepted\"}");
    return new WsRuntimeAdapterTest.StubProcess(
        "{\"sessionID\":\"" + sessionId + "\",\"type\":\"session\"}\n"
            + "{\"type\":\"message\",\"part\":{\"type\":\"text\",\"text\":"
            + terminal
            + "}}\n",
        "",
        0);
  }
}
