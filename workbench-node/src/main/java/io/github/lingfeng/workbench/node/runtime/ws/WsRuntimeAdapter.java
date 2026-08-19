package io.github.lingfeng.workbench.node.runtime.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.runtime.RuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.RuntimeCapabilities;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.RuntimeEventSink;
import io.github.lingfeng.workbench.node.runtime.RuntimeExecutionContext;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.RuntimeResumeContext;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WsRuntimeAdapter implements RuntimeAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WsRuntimeAdapter.class);

    private final String executable;
    private final ObjectMapper objectMapper;
    private final RuntimeProcessLauncher processLauncher;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public WsRuntimeAdapter(String executable, ObjectMapper objectMapper) {
        this(executable, objectMapper, command -> new ProcessBuilder(command).start());
    }

    WsRuntimeAdapter(String executable, ObjectMapper objectMapper, RuntimeProcessLauncher processLauncher) {
        this.executable = executable;
        this.objectMapper = objectMapper;
        this.processLauncher = processLauncher;
    }

    @Override
    public RuntimeProbe probe() {
        Process process;
        try {
            process = processLauncher.start(List.of(executable, "--version"));
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
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

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities("ws", Set.of(
                "runtime:ws", "structured-events", "resume", "cancel", "persistent-session"));
    }

    @Override
    public void start(RuntimeExecutionContext context, RuntimeEventSink eventSink) {
        Assignment assignment = context.assignment();
        List<String> command = List.of(
                executable,
                "run",
                "--format",
                "json",
                "--dir",
                context.workspace().toString(),
                initialPrompt(assignment));
        execute(command, assignment, context.evidenceDirectory(), eventSink);
    }

    @Override
    public void resume(RuntimeResumeContext context, RuntimeEventSink eventSink) {
        List<String> command = List.of(
                executable,
                "run",
                "--format",
                "json",
                "--session",
                context.runtimeSessionRef(),
                "--dir",
                context.workspace().toString(),
                resumePrompt(context));
        execute(command, context.assignment(), context.evidenceDirectory(), eventSink);
    }

    @Override
    public void cancel() {
        Process process = activeProcess.get();
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    private void execute(
            List<String> command,
            Assignment assignment,
            Path evidenceDirectory,
            RuntimeEventSink eventSink) {
        Process process;
        try {
            process = processLauncher.start(command);
        } catch (IOException exception) {
            eventSink.emit(new RuntimeEvent.Failed("WS failed to start", RuntimeEvent.AcceptanceStatus.UNKNOWN));
            return;
        }
        if (!activeProcess.compareAndSet(null, process)) {
            process.destroyForcibly();
            throw new IllegalStateException("A WS process is already active");
        }
        Path stderrPath = evidenceDirectory.resolve("runtime-stderr.log");
        Thread stderrThread = Thread.ofPlatform()
                .name("workbench-ws-stderr")
                .daemon(true)
                .start(() -> copyStderr(process.getErrorStream(), stderrPath));
        try {
            boolean trustedTerminal = consumeStdout(
                    process.getInputStream(), assignment, evidenceDirectory, eventSink);
            int exitCode = process.waitFor();
            if (!trustedTerminal) {
                String summary = exitCode == 0
                        ? "WS exited without a trusted structured terminal"
                        : "WS process failed; details remain in the local runtime log";
                eventSink.emit(new RuntimeEvent.Failed(summary, RuntimeEvent.AcceptanceStatus.UNKNOWN));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroy();
            eventSink.emit(new RuntimeEvent.Interrupted("WS execution was interrupted"));
        } catch (IOException exception) {
            process.destroy();
            eventSink.emit(new RuntimeEvent.Failed(
                    "WS event stream could not be recorded",
                    RuntimeEvent.AcceptanceStatus.UNKNOWN));
        } finally {
            activeProcess.compareAndSet(process, null);
            try {
                stderrThread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean consumeStdout(
            InputStream stdout,
            Assignment assignment,
            Path evidenceDirectory,
            RuntimeEventSink eventSink) throws IOException {
        Path runtimeEventsPath = evidenceDirectory.resolve("runtime-events.ndjson");
        Path resultPath = evidenceDirectory.resolve("result.md");
        List<String> fullText = new ArrayList<>();
        boolean terminalSeen = false;
        String emittedSessionId = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLine(runtimeEventsPath, line);
                JsonNode decoded;
                try {
                    decoded = objectMapper.readTree(line);
                } catch (JsonProcessingException exception) {
                    continue;
                }
                if (decoded == null || !decoded.isObject()) {
                    continue;
                }
                String sessionId = decoded.path("sessionID").asText("");
                if (!sessionId.isBlank() && !sessionId.equals(emittedSessionId)) {
                    emittedSessionId = sessionId;
                    eventSink.emit(new RuntimeEvent.Started(true, sessionId));
                }
                RuntimeEvent terminal = WsTerminalInterpreter.interpret(decoded, assignment.missionDigest());
                if (terminal != null) {
                    terminalSeen = true;
                    eventSink.emit(terminal);
                    continue;
                }
                JsonNode part = decoded.path("part");
                if (part.isObject() && "text".equals(part.path("type").asText())) {
                    String text = part.path("text").asText();
                    if (!text.isBlank()) {
                        fullText.add(text);
                        RuntimeEvent embeddedTerminal = interpretEmbeddedTerminal(text, assignment.missionDigest());
                        if (embeddedTerminal != null) {
                            terminalSeen = true;
                            eventSink.emit(embeddedTerminal);
                        } else {
                            eventSink.emit(new RuntimeEvent.Progress(
                                    WsTerminalInterpreter.compactSummary(text, "WS is running")));
                        }
                    }
                }
                if (isInteraction(decoded)) {
                    eventSink.emit(new RuntimeEvent.InteractionRequested(
                            decoded.path("checkpointId").asText("ws-checkpoint"),
                            WsTerminalInterpreter.compactSummary(
                                    decoded.path("description").asText(),
                                    "WS requested user input")));
                }
            }
        }
        Files.writeString(resultPath, String.join("", fullText), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return terminalSeen;
    }

    private static boolean isInteraction(JsonNode decoded) {
        String type = decoded.path("type").asText();
        return type.equals("permission") || type.equals("permission_asked")
                || type.equals("permission.requested") || type.equals("approval_required");
    }

    private RuntimeEvent interpretEmbeddedTerminal(String text, String expectedMissionDigest) {
        try {
            JsonNode decoded = objectMapper.readTree(text.strip());
            if (decoded == null || !decoded.isObject()) {
                return null;
            }
            return WsTerminalInterpreter.interpret(decoded, expectedMissionDigest);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static void appendLine(Path path, String line) throws IOException {
        Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void copyStderr(InputStream stderr, Path destination) {
        try (InputStream source = stderr) {
            Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            logger.warn("Unable to preserve WS stderr as local evidence", exception);
        }
    }

    private static String initialPrompt(Assignment assignment) {
        return ("Mission objective: %s | Acceptance summary: %s | Authorized side effects: %s | "
                + "Complete the mission under the selected runtime profile. Do not assume that process exit means "
                + "acceptance. Emit exactly one JSON event with type=lingfeng.terminal, missionDigest=%s, "
                + "runtimeOutcome, acceptanceStatus, and a resultSummary of at most 800 characters.").formatted(
                assignment.objective(),
                assignment.acceptanceSummary(),
                assignment.authorizedSideEffectsSummary(),
                assignment.missionDigest());
    }

    private static String resumePrompt(RuntimeResumeContext context) {
        Assignment assignment = context.assignment();
        return ("Resume the same immutable Mission. Mission digest: %s | Objective: %s | Acceptance summary: %s | "
                + "Authorized side effects: %s | Exact interaction response: %s | Emit the same structured "
                + "lingfeng.terminal event required by the original Mission.").formatted(
                assignment.missionDigest(),
                assignment.objective(),
                assignment.acceptanceSummary(),
                assignment.authorizedSideEffectsSummary(),
                context.interactionResponse());
    }
}
