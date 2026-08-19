package io.github.lingfeng.workbench.node.runtime.ws;

import static io.github.lingfeng.workbench.node.TestAssignments.DIGEST;
import static io.github.lingfeng.workbench.node.TestAssignments.assignment;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.runtime.RuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.RuntimeExecutionContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsEmbeddedStructuredTerminalAndKeepsRawEvidenceLocal() throws Exception {
        Path evidenceDirectory = createEvidenceDirectory();
        String terminal = """
                {"type":"lingfeng.terminal","missionDigest":"%s","runtimeOutcome":"SUCCEEDED",\
                "acceptanceStatus":"PASSED","resultSummary":"accepted"}
                """.formatted(DIGEST).strip();
        String stdout = """
                {"sessionID":"private-session","type":"session"}
                {"type":"message","part":{"type":"text","text":%s}}
                """.formatted(objectMapper.writeValueAsString(terminal));
        StubProcess process = new StubProcess(stdout, "private stderr", 0);
        AtomicReference<List<String>> launchedCommand = new AtomicReference<>();
        WsRuntimeAdapter adapter = new WsRuntimeAdapter("ws", objectMapper, command -> {
            launchedCommand.set(command);
            return process;
        });
        List<RuntimeEvent> events = new ArrayList<>();

        adapter.start(new RuntimeExecutionContext(
                assignment(), temporaryDirectory.resolve("workspace"), evidenceDirectory), events::add);

        assertThat(events).contains(
                new RuntimeEvent.Started(true, "private-session"),
                new RuntimeEvent.Finished(
                        RuntimeEvent.RuntimeOutcome.SUCCEEDED,
                        RuntimeEvent.AcceptanceStatus.PASSED,
                        "accepted"));
        assertThat(Files.readString(evidenceDirectory.resolve("runtime-events.ndjson")))
                .contains("private-session", "lingfeng.terminal");
        assertThat(Files.readString(evidenceDirectory.resolve("runtime-stderr.log")))
                .isEqualTo("private stderr");
        assertThat(Files.readString(evidenceDirectory.resolve("result.md")))
                .contains("lingfeng.terminal");
        assertThat(launchedCommand.get().getLast())
                .contains(DIGEST, "Acceptance summary", "Authorized side effects")
                .doesNotContain("\r", "\n");
    }

    @Test
    void successfulProcessExitWithoutStructuredTerminalIsUnknownFailure() throws Exception {
        Path evidenceDirectory = createEvidenceDirectory();
        String stdout = """
                {"sessionID":"private-session","type":"session"}
                {"type":"message","part":{"type":"text","text":"looks complete"}}
                """;
        WsRuntimeAdapter adapter = new WsRuntimeAdapter(
                "ws", objectMapper, command -> new StubProcess(stdout, "", 0));
        List<RuntimeEvent> events = new ArrayList<>();

        adapter.start(new RuntimeExecutionContext(
                assignment(), temporaryDirectory.resolve("workspace"), evidenceDirectory), events::add);

        assertThat(events.getLast()).isEqualTo(new RuntimeEvent.Failed(
                "WS exited without a trusted structured terminal",
                RuntimeEvent.AcceptanceStatus.UNKNOWN));
        assertThat(events).doesNotContain(new RuntimeEvent.Finished(
                RuntimeEvent.RuntimeOutcome.SUCCEEDED,
                RuntimeEvent.AcceptanceStatus.PASSED,
                "looks complete"));
    }

    private Path createEvidenceDirectory() throws Exception {
        Path evidenceDirectory = temporaryDirectory.resolve("evidence");
        Files.createDirectories(evidenceDirectory);
        Files.createFile(evidenceDirectory.resolve("runtime-events.ndjson"));
        Files.createFile(evidenceDirectory.resolve("runtime-stderr.log"));
        Files.createFile(evidenceDirectory.resolve("result.md"));
        return evidenceDirectory;
    }

    private static final class StubProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;
        private boolean alive = true;

        private StubProcess(String stdout, String stderr, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
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
    }
}
