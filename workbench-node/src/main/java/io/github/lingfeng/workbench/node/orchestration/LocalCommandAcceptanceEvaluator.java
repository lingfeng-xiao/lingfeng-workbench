package io.github.lingfeng.workbench.node.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.orchestration.AcceptanceProfileRegistry.AcceptanceProfile;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class LocalCommandAcceptanceEvaluator implements AcceptanceEvaluator {

    private static final String REPORT_NAME = "acceptance-report.json";

    private final AcceptanceProfileRegistry profileRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LocalCommandAcceptanceEvaluator(
            AcceptanceProfileRegistry profileRegistry, ObjectMapper objectMapper, Clock clock) {
        this.profileRegistry = profileRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CompletionStage<AcceptanceResult> evaluate(
            SessionContext context, SessionHandle session, String runtimeSummary) {
        CompletableFuture<AcceptanceResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("acceptance-" + context.command().binding().runId()).start(() -> {
            try {
                future.complete(evaluateProfile(context));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private AcceptanceResult evaluateProfile(SessionContext context) throws IOException, InterruptedException {
        String profileId = context.command().executionProfile();
        AcceptanceProfile profile = profileRegistry.find(profileId).orElse(null);
        if (profile == null) {
            AcceptanceResult unknown = new AcceptanceResult(
                    NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                    "No trusted local acceptance profile is configured for " + profileId);
            writeReport(context, reportWithoutCommand(profileId, unknown));
            return unknown;
        }
        return executeProfile(context, profile);
    }

    private AcceptanceResult executeProfile(
            SessionContext context, AcceptanceProfile profile) throws IOException, InterruptedException {
        Instant startedAt = Instant.now(clock);
        long startedNanos = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(profile.command())
                    .directory(context.workspace().toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start();
            process.getOutputStream().close();
        } catch (IOException exception) {
            AcceptanceResult unknown = new AcceptanceResult(
                    NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                    "Trusted acceptance profile " + profile.profileId() + " could not start");
            writeReport(context, reportStartFailure(profile, startedAt, unknown, exception));
            return unknown;
        }

        StreamCapture stdout = new StreamCapture(process.getInputStream(), profile.maxOutputBytes());
        StreamCapture stderr = new StreamCapture(process.getErrorStream(), profile.maxOutputBytes());
        Thread stdoutReader = Thread.ofVirtual().start(stdout);
        Thread stderrReader = Thread.ofVirtual().start(stderr);
        boolean finished = process.waitFor(profile.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
        stdoutReader.join();
        stderrReader.join();

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        Map<String, Boolean> artifacts = inspectArtifacts(context.workspace(), profile);
        AcceptanceResult acceptance = decide(profile, finished, process.exitValue(), artifacts);
        ObjectNode report = reportCompleted(
                profile, startedAt, durationMs, finished, process.exitValue(), artifacts,
                stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated(), acceptance);
        writeReport(context, report);
        return acceptance;
    }

    private Map<String, Boolean> inspectArtifacts(Path workspace, AcceptanceProfile profile) {
        Map<String, Boolean> inspected = new LinkedHashMap<>();
        for (String artifact : profile.requiredArtifacts()) {
            Path resolved = workspace.resolve(artifact).normalize();
            inspected.put(artifact, resolved.startsWith(workspace) && Files.exists(resolved));
        }
        return Map.copyOf(inspected);
    }

    private AcceptanceResult decide(
            AcceptanceProfile profile, boolean finished, int exitCode, Map<String, Boolean> artifacts) {
        if (!finished) {
            return new AcceptanceResult(
                    NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN,
                    "Trusted acceptance profile " + profile.profileId() + " timed out");
        }
        if (exitCode != 0) {
            return new AcceptanceResult(
                    NormalizedRuntimeEvent.AcceptanceStatus.FAILED,
                    "Trusted acceptance profile " + profile.profileId() + " failed with exit code " + exitCode);
        }
        long missingArtifacts = artifacts.values().stream().filter(present -> !present).count();
        if (missingArtifacts > 0) {
            return new AcceptanceResult(
                    NormalizedRuntimeEvent.AcceptanceStatus.FAILED,
                    "Trusted acceptance profile " + profile.profileId()
                            + " is missing " + missingArtifacts + " required artifact(s)");
        }
        return new AcceptanceResult(
                NormalizedRuntimeEvent.AcceptanceStatus.PASSED,
                "Trusted acceptance profile " + profile.profileId() + " passed");
    }

    private ObjectNode reportWithoutCommand(String profileId, AcceptanceResult acceptance) {
        ObjectNode report = reportHeader(profileId, Instant.now(clock), acceptance);
        report.put("configured", false);
        return report;
    }

    private ObjectNode reportStartFailure(
            AcceptanceProfile profile,
            Instant startedAt,
            AcceptanceResult acceptance,
            IOException exception) {
        ObjectNode report = reportHeader(profile.profileId(), startedAt, acceptance);
        report.put("configured", true);
        report.put("started", false);
        report.put("failureType", exception.getClass().getSimpleName());
        return report;
    }

    private ObjectNode reportCompleted(
            AcceptanceProfile profile,
            Instant startedAt,
            long durationMs,
            boolean finished,
            int exitCode,
            Map<String, Boolean> artifacts,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            AcceptanceResult acceptance) {
        ObjectNode report = reportHeader(profile.profileId(), startedAt, acceptance);
        report.put("configured", true);
        report.put("started", true);
        report.put("finished", finished);
        report.put("durationMs", durationMs);
        report.put("exitCode", exitCode);
        report.put("stdout", stdout);
        report.put("stderr", stderr);
        report.put("stdoutTruncated", stdoutTruncated);
        report.put("stderrTruncated", stderrTruncated);
        ArrayNode artifactChecks = report.putArray("requiredArtifacts");
        artifacts.forEach((artifact, present) -> artifactChecks.addObject()
                .put("path", artifact)
                .put("present", present));
        return report;
    }

    private ObjectNode reportHeader(
            String profileId, Instant startedAt, AcceptanceResult acceptance) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("schemaVersion", "1.0");
        report.put("profileId", profileId);
        report.put("startedAt", startedAt.toString());
        report.put("recordedAt", Instant.now(clock).toString());
        report.put("status", acceptance.status().name());
        report.put("summary", acceptance.summary());
        return report;
    }

    private void writeReport(SessionContext context, ObjectNode report) throws IOException {
        Path evidenceDirectory = context.evidenceDirectory().toAbsolutePath().normalize();
        Files.createDirectories(evidenceDirectory);
        Path reportPath = evidenceDirectory.resolve(REPORT_NAME);
        Path pendingPath = evidenceDirectory.resolve(REPORT_NAME + ".pending");
        Files.writeString(pendingPath, objectMapper.writeValueAsString(report), StandardCharsets.UTF_8);
        try {
            Files.move(pendingPath, reportPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(pendingPath, reportPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class StreamCapture implements Runnable {
        private final InputStream stream;
        private final int limit;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private StreamCapture(InputStream stream, int limit) {
            this.stream = stream;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = limit - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException exception) {
                truncated = true;
            }
        }

        private String text() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
