package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.V2TestCommands.DIGEST;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WsRuntimeAdapterTest {

  @TempDir Path temporaryDirectory;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void readsFinalStructuredTerminalAndKeepsRawEvidenceLocal() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String terminal =
        """
        {"type":"lingfeng.terminal","missionDigest":"%s","runtimeOutcome":"SUCCEEDED",\
        "acceptanceStatus":"PASSED","resultSummary":"accepted"}
        """
            .formatted(DIGEST)
            .strip();
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":%s}}
        """
            .formatted(objectMapper.writeValueAsString(terminal));
    AtomicReference<List<String>> launchedCommand = new AtomicReference<>();
    AtomicReference<Path> workingDirectory = new AtomicReference<>();
    AtomicReference<StubProcess> launchedProcess = new AtomicReference<>();
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws",
            objectMapper,
            (command, directory) -> {
              launchedCommand.set(command);
              workingDirectory.set(directory);
              StubProcess process = new StubProcess(stdout, "private stderr", 0);
              launchedProcess.set(process);
              return process;
            });
    List<NormalizedRuntimeEvent> events = new ArrayList<>();
    SessionContext context =
        new SessionContext(
            start(), temporaryDirectory.resolve("workspace"), evidenceDirectory);

    String sessionId =
        adapter.executeTurn(context, new TurnInput("turn-3", 3, "finish"), null, events::add);

    assertThat(sessionId).isEqualTo("private-session");
    assertThat(events)
        .contains(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                "accepted"));
    assertThat(Files.readString(evidenceDirectory.resolve("runtime-events.ndjson")))
        .contains("private-session", "lingfeng.terminal");
    assertThat(Files.readString(evidenceDirectory.resolve("runtime-stderr.log")))
        .isEqualTo("private stderr" + System.lineSeparator());
    assertThat(Files.readString(evidenceDirectory.resolve("result.md")))
        .contains("turn-3", "lingfeng.terminal");
    assertThat(launchedCommand.get())
        .doesNotContain("--dir")
        .anySatisfy(
            argument ->
                assertThat(argument)
                    .contains(
                        DIGEST,
                        "local Workbench task",
                        "Acceptance criteria",
                        "Allowed side effects",
                        context.workspace().toAbsolutePath().normalize().toString(),
                        "Use only this directory for all file and shell tools",
                        "Return exactly one JSON object",
                        "SUCCEEDED, FAILED, INTERRUPTED, UNKNOWN",
                        "PASSED, FAILED, UNKNOWN")
                    .doesNotContain("\r", "\n"));
    assertThat(workingDirectory.get()).isEqualTo(context.workspace());
    assertThat(launchedProcess.get().stdinClosed()).isTrue();
  }

  @Test
  void successfulFinalProcessWithoutStructuredTerminalIsUnknown() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":"looks complete"}}
        """;
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws", objectMapper, (command, directory) -> new StubProcess(stdout, "", 0));
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    adapter.executeTurn(
        new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidenceDirectory),
        new TurnInput("turn-3", 3, "finish"),
        null,
        events::add);

    assertThat(events.getLast())
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "WS exited without a trusted structured terminal"));
  }

  @Test
  void readsStructuredTerminalAfterAssistantSummary() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String terminal =
        """
        {"type":"lingfeng.terminal","missionDigest":"%s","runtimeOutcome":"SUCCEEDED",\
        "acceptanceStatus":"PASSED","resultSummary":"accepted after summary"}
        """
            .formatted(DIGEST)
            .strip();
    String assistantText = "All acceptance criteria were verified.\n\n" + terminal;
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":%s}}
        """
            .formatted(objectMapper.writeValueAsString(assistantText));
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws", objectMapper, (command, directory) -> new StubProcess(stdout, "", 0));
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    adapter.executeTurn(
        new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidenceDirectory),
        new TurnInput("turn-3", 3, "finish"),
        null,
        events::add);

    assertThat(events)
        .contains(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED,
                NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                "accepted after summary"))
        .doesNotContain(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "WS exited without a trusted structured terminal"));
  }

  @Test
  void rejectsWrongDigestTerminalAfterAssistantSummary() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String wrongDigest = "b".repeat(64);
    String terminal =
        """
        {"type":"lingfeng.terminal","missionDigest":"%s","runtimeOutcome":"SUCCEEDED",\
        "acceptanceStatus":"PASSED","resultSummary":"wrong digest"}
        """
            .formatted(wrongDigest)
            .strip();
    String assistantText = "All acceptance criteria were verified.\n\n" + terminal;
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":%s}}
        """
            .formatted(objectMapper.writeValueAsString(assistantText));
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws", objectMapper, (command, directory) -> new StubProcess(stdout, "", 0));
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    adapter.executeTurn(
        new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidenceDirectory),
        new TurnInput("turn-3", 3, "finish"),
        null,
        events::add);

    assertThat(events.getLast())
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                wrongDigest,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "Runtime terminal mission digest did not match"));
    assertThat(events)
        .doesNotContain(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "WS exited without a trusted structured terminal"));
  }

  @Test
  void rejectsInvalidStatusTerminalAfterAssistantSummary() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String terminal =
        """
        {"type":"lingfeng.terminal","missionDigest":"%s","runtimeOutcome":"BOGUS",\
        "acceptanceStatus":"PASSED","resultSummary":"bad status"}
        """
            .formatted(DIGEST)
            .strip();
    String assistantText = "All acceptance criteria were verified.\n\n" + terminal;
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":%s}}
        """
            .formatted(objectMapper.writeValueAsString(assistantText));
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws", objectMapper, (command, directory) -> new StubProcess(stdout, "", 0));
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    adapter.executeTurn(
        new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidenceDirectory),
        new TurnInput("turn-3", 3, "finish"),
        null,
        events::add);

    assertThat(events.getLast())
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "Runtime terminal contains unsupported status"));
    assertThat(events)
        .doesNotContain(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "WS exited without a trusted structured terminal"));
  }

  @Test
  void rejectsMalformedTerminalAfterAssistantSummary() throws Exception {
    Path evidenceDirectory = createEvidenceDirectory();
    String assistantText = "All acceptance criteria were verified.\n\n{not valid json}";
    String stdout =
        """
        {"sessionID":"private-session","type":"session"}
        {"type":"message","part":{"type":"text","text":%s}}
        """
            .formatted(objectMapper.writeValueAsString(assistantText));
    WsRuntimeAdapter adapter =
        new WsRuntimeAdapter(
            "ws", objectMapper, (command, directory) -> new StubProcess(stdout, "", 0));
    List<NormalizedRuntimeEvent> events = new ArrayList<>();

    adapter.executeTurn(
        new SessionContext(start(), temporaryDirectory.resolve("workspace"), evidenceDirectory),
        new TurnInput("turn-3", 3, "finish"),
        null,
        events::add);

    assertThat(events.getLast())
        .isEqualTo(
            new NormalizedRuntimeEvent.Terminal(
                DIGEST,
                NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
                NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                "WS exited without a trusted structured terminal"));
  }

  private Path createEvidenceDirectory() throws Exception {
    Path evidenceDirectory = temporaryDirectory.resolve("evidence");
    Files.createDirectories(evidenceDirectory);
    return evidenceDirectory;
  }

  static final class StubProcess extends Process {

    private final InputStream stdout;
    private final InputStream stderr;
    private final int exitCode;
    private final OutputStream stdin =
        new ByteArrayOutputStream() {
          @Override
          public void close() throws IOException {
            stdinClosed = true;
            super.close();
          }
        };
    private boolean alive = true;
    private boolean stdinClosed;

    StubProcess(String stdout, String stderr, int exitCode) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return stdin;
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return stderr;
    }

    @Override
    public int waitFor() {
      alive = false;
      return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      alive = false;
      return true;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      alive = false;
    }

    @Override
    public Process destroyForcibly() {
      alive = false;
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    boolean stdinClosed() {
      return stdinClosed;
    }
  }
}
