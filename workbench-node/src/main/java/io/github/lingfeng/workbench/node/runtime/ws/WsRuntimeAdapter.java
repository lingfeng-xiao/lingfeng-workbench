package io.github.lingfeng.workbench.node.runtime.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.evidence.BoundedEvidenceWriter;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.TurnInput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WsRuntimeAdapter {

  private static final Logger logger = LoggerFactory.getLogger(WsRuntimeAdapter.class);
  private static final int FINAL_TURN = 3;

  private final String executable;
  private final ObjectMapper objectMapper;
  private final RuntimeProcessLauncher processLauncher;
  private final AtomicReference<Process> activeProcess = new AtomicReference<>();

  public WsRuntimeAdapter(String executable, ObjectMapper objectMapper) {
    this(
        executable,
        objectMapper,
        (command, workingDirectory) ->
            new ProcessBuilder(command).directory(workingDirectory.toFile()).start());
  }

  WsRuntimeAdapter(
      String executable, ObjectMapper objectMapper, RuntimeProcessLauncher processLauncher) {
    this.executable = executable;
    this.objectMapper = objectMapper;
    this.processLauncher = processLauncher;
  }

  public RuntimeProbe probe() {
    Process process;
    try {
      process = processLauncher.start(List.of(executable, "--version"), Path.of("."));
      process.getOutputStream().close();
      boolean finished = process.waitFor(15, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return new RuntimeProbe(false, "WS version probe timed out");
      }
      return new RuntimeProbe(process.exitValue() == 0, "WS probe exit=" + process.exitValue());
    } catch (IOException exception) {
      return new RuntimeProbe(false, "WS executable is unavailable");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new RuntimeProbe(false, "WS version probe was interrupted");
    }
  }

  public String executeTurn(
      SessionContext context,
      TurnInput turn,
      String runtimeSessionId,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    NodeCommand.StartRun command = context.command();
    List<String> processCommand = new ArrayList<>();
    processCommand.add(executable);
    processCommand.add("run");
    processCommand.add("--format");
    processCommand.add("json");
    if (runtimeSessionId != null) {
      processCommand.add("--session");
      processCommand.add(runtimeSessionId);
    }
    processCommand.add(prompt(command, turn));
    return execute(
        List.copyOf(processCommand), context, turn, runtimeSessionId, eventSink);
  }

  public void cancel() {
    Process process = activeProcess.get();
    if (process != null && process.isAlive()) {
      process.destroy();
    }
  }

  private String execute(
      List<String> command,
      SessionContext context,
      TurnInput turn,
      String expectedSessionId,
      Consumer<NormalizedRuntimeEvent> eventSink) {
    Process process;
    try {
      process = processLauncher.start(command, context.workspace());
    } catch (IOException exception) {
      eventSink.accept(unknownTerminal(context, "WS failed to start"));
      return expectedSessionId;
    }
    try {
      process.getOutputStream().close();
    } catch (IOException exception) {
      process.destroyForcibly();
      eventSink.accept(unknownTerminal(context, "WS stdin could not be closed"));
      return expectedSessionId;
    }
    if (!activeProcess.compareAndSet(null, process)) {
      process.destroyForcibly();
      throw new IllegalStateException("A WS process is already active");
    }
    Thread stderrThread =
        Thread.ofPlatform()
            .name("workbench-ws-stderr")
            .daemon(true)
            .start(
                () ->
                    copyStderr(
                        process.getErrorStream(),
                        context.evidenceDirectory().resolve("runtime-stderr.log")));
    try {
      StreamResult stream =
          consumeStdout(process.getInputStream(), context, turn, eventSink);
      int exitCode = process.waitFor();
      String observedSessionId = stream.sessionId();
      if (expectedSessionId != null
          && observedSessionId != null
          && !expectedSessionId.equals(observedSessionId)) {
        eventSink.accept(unknownTerminal(context, "WS continued a different Agent Session"));
        return expectedSessionId;
      }
      String selectedSessionId = expectedSessionId == null ? observedSessionId : expectedSessionId;
      if (selectedSessionId == null) {
        eventSink.accept(unknownTerminal(context, "WS did not expose a durable Agent Session ID"));
      } else if (exitCode != 0) {
        eventSink.accept(
            unknownTerminal(context, "WS process failed; details remain in the local runtime log"));
      } else if (turn.turnNumber() == FINAL_TURN && !stream.terminalSeen()) {
        eventSink.accept(
            unknownTerminal(context, "WS exited without a trusted structured terminal"));
      } else if (turn.turnNumber() < FINAL_TURN && stream.terminalSeen()) {
        eventSink.accept(
            unknownTerminal(context, "WS emitted a terminal before the required final Turn"));
      }
      return selectedSessionId;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroy();
      eventSink.accept(unknownTerminal(context, "WS execution was interrupted"));
      return expectedSessionId;
    } catch (IOException exception) {
      process.destroy();
      eventSink.accept(unknownTerminal(context, "WS event stream could not be recorded"));
      return expectedSessionId;
    } finally {
      activeProcess.compareAndSet(process, null);
      try {
        stderrThread.join(2_000);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private StreamResult consumeStdout(
      InputStream stdout,
      SessionContext context,
      TurnInput turn,
      Consumer<NormalizedRuntimeEvent> eventSink)
      throws IOException {
    Path runtimeEventsPath = context.evidenceDirectory().resolve("runtime-events.ndjson");
    List<String> turnText = new ArrayList<>();
    boolean terminalSeen = false;
    String sessionId = null;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        BoundedEvidenceWriter.appendLine(runtimeEventsPath, line);
        JsonNode decoded = decode(line);
        if (decoded == null || !decoded.isObject()) {
          continue;
        }
        String candidateSessionId = decoded.path("sessionID").asText("");
        if (!candidateSessionId.isBlank()) {
          sessionId = candidateSessionId;
        }
        NormalizedRuntimeEvent.Terminal terminal =
            WsTerminalInterpreter.interpret(decoded, context.command().binding().missionDigest());
        if (terminal != null) {
          terminalSeen = true;
          if (turn.turnNumber() == FINAL_TURN) {
            eventSink.accept(terminal);
          }
          continue;
        }
        JsonNode part = decoded.path("part");
        if (part.isObject() && "text".equals(part.path("type").asText())) {
          String text = part.path("text").asText();
          if (!text.isBlank()) {
            turnText.add(text);
            NormalizedRuntimeEvent.Terminal embeddedTerminal =
                interpretEmbeddedTerminal(text, context.command().binding().missionDigest());
            if (embeddedTerminal != null) {
              terminalSeen = true;
              if (turn.turnNumber() == FINAL_TURN) {
                eventSink.accept(embeddedTerminal);
              }
            } else {
              eventSink.accept(
                  new NormalizedRuntimeEvent.ProgressUpdated(
                      WsTerminalInterpreter.compactSummary(text, "WS is running")));
            }
          }
        }
      }
    }
    appendTurnResult(context.evidenceDirectory().resolve("result.md"), turn, turnText);
    return new StreamResult(sessionId, terminalSeen);
  }

  private JsonNode decode(String line) {
    try {
      return objectMapper.readTree(line);
    } catch (JsonProcessingException exception) {
      return null;
    }
  }

  private NormalizedRuntimeEvent.Terminal interpretEmbeddedTerminal(
      String text, String expectedMissionDigest) {
    JsonNode decoded = decode(text.strip());
    return decoded == null
        ? null
        : WsTerminalInterpreter.interpret(decoded, expectedMissionDigest);
  }

  private static void appendTurnResult(Path resultPath, TurnInput turn, List<String> turnText)
      throws IOException {
    String content =
        "## " + turn.turnId() + System.lineSeparator() + String.join("", turnText)
            + System.lineSeparator();
    Files.writeString(
        resultPath,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static void copyStderr(InputStream stderr, Path destination) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        BoundedEvidenceWriter.appendLine(destination, line);
      }
    } catch (IOException exception) {
      logger.warn("Unable to preserve WS stderr as local evidence", exception);
    }
  }

  private static String prompt(NodeCommand.StartRun command, TurnInput turn) {
    String taskContext =
        ("You are completing a local Workbench task. Task: %s Acceptance criteria: %s "
                + "Allowed side effects: %s Task reference digest: %s ")
            .formatted(
                command.objective(),
                command.acceptanceSummary(),
                command.authorizedSideEffectsSummary(),
                command.binding().missionDigest());
    if (turn.turnNumber() < FINAL_TURN) {
      return (taskContext
              + "This is step %d of %d in the same Agent Session. Work on the task, stay within the allowed side effects, and reply with one concise progress sentence. Keep the task open for the next step and do not report final completion yet.")
          .formatted(turn.turnNumber(), FINAL_TURN)
          .replaceAll("[\\r\\n]+", " ");
    }
    return (taskContext
            + "This is the final step %d of %d in the same Agent Session. Complete the task and evaluate the acceptance criteria honestly. Return exactly one JSON object with type=lingfeng.terminal and missionDigest=%s. runtimeOutcome must be exactly one uppercase value from SUCCEEDED, FAILED, INTERRUPTED, UNKNOWN. acceptanceStatus must be exactly one uppercase value from PASSED, FAILED, UNKNOWN, and PASSED is valid only with SUCCEEDED. Include resultSummary of at most 800 characters. Do not wrap the JSON in markdown or add surrounding prose.")
        .formatted(FINAL_TURN, FINAL_TURN, command.binding().missionDigest())
        .replaceAll("[\\r\\n]+", " ");
  }

  private static NormalizedRuntimeEvent.Terminal unknownTerminal(
      SessionContext context, String summary) {
    return new NormalizedRuntimeEvent.Terminal(
        context.command().binding().missionDigest(),
        NormalizedRuntimeEvent.RuntimeOutcome.UNKNOWN,
        NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
        summary);
  }

  private record StreamResult(String sessionId, boolean terminalSeen) {}
}
