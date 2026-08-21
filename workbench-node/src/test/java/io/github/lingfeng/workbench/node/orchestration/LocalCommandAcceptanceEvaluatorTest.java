package io.github.lingfeng.workbench.node.orchestration;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lingfeng.workbench.node.config.AcceptanceProperties;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import io.github.lingfeng.workbench.node.runtime.session.SessionContext;
import io.github.lingfeng.workbench.node.runtime.session.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCommandAcceptanceEvaluatorTest {

    private static final String PROFILE_ID = "trusted-test-v1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void passesOnlyAfterConfiguredCommandAndRequiredArtifactsPass() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace-pass"));
        Files.writeString(workspace.resolve("verified.txt"), "runtime artifact");
        LocalCommandAcceptanceEvaluator evaluator = evaluator(profile(
                javaCommand("pass", "secret-command-argument"), Duration.ofSeconds(5),
                List.of("verified.txt")));

        AcceptanceResult acceptance = evaluator.evaluate(
                context(workspace), session(workspace), "assistant says done")
                .toCompletableFuture().join();

        assertThat(acceptance.status()).isEqualTo(NormalizedRuntimeEvent.AcceptanceStatus.PASSED);
        String report = Files.readString(evidenceDirectory().resolve("acceptance-report.json"));
        assertThat(report)
                .contains("\"profileId\":\"trusted-test-v1\"")
                .contains("\"status\":\"PASSED\"")
                .contains("\"present\":true")
                .doesNotContain("assistant says done", "secret-command-argument");
    }

    @Test
    void failsForNonZeroExitAndMissingRequiredArtifact() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace-fail"));
        LocalCommandAcceptanceEvaluator evaluator = evaluator(profile(
                javaCommand("fail"), Duration.ofSeconds(5), List.of("missing.txt")));

        AcceptanceResult acceptance = evaluator.evaluate(
                context(workspace), session(workspace), "ignored")
                .toCompletableFuture().join();

        assertThat(acceptance.status()).isEqualTo(NormalizedRuntimeEvent.AcceptanceStatus.FAILED);
        assertThat(acceptance.summary()).contains("exit code 7");
        assertThat(Files.readString(evidenceDirectory().resolve("acceptance-report.json")))
                .contains("\"exitCode\":7")
                .contains("\"present\":false");
    }

    @Test
    void returnsUnknownForTimeoutAndForUnconfiguredProfile() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace-unknown"));
        LocalCommandAcceptanceEvaluator timed = evaluator(profile(
                javaCommand("sleep"), Duration.ofMillis(100), List.of()));

        AcceptanceResult timeout = timed.evaluate(
                context(workspace), session(workspace), "ignored")
                .toCompletableFuture().join();
        LocalCommandAcceptanceEvaluator unconfigured = evaluator(Map.of());
        AcceptanceResult missing = unconfigured.evaluate(
                context(workspace), session(workspace), "ignored")
                .toCompletableFuture().join();

        assertThat(timeout.status()).isEqualTo(NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN);
        assertThat(timeout.summary()).contains("timed out");
        assertThat(missing.status()).isEqualTo(NormalizedRuntimeEvent.AcceptanceStatus.UNKNOWN);
        assertThat(missing.summary()).contains("No trusted local acceptance profile");
    }

    @Test
    void rejectsShellLikeOrEscapingProfileConfiguration() {
        assertThatThrownBy(() -> evaluator(profile(
                List.of(), Duration.ofSeconds(1), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
        assertThatThrownBy(() -> evaluator(profile(
                javaCommand("pass"), Duration.ofSeconds(1), List.of("../outside.txt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside the workspace");
        assertThatThrownBy(() -> evaluator(profile(
                javaCommand("pass"), Duration.ofHours(1), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30 minutes");
    }

    private LocalCommandAcceptanceEvaluator evaluator(
            Map<String, AcceptanceProperties.Profile> profiles) {
        return new LocalCommandAcceptanceEvaluator(
                new AcceptanceProfileRegistry(new AcceptanceProperties(profiles)),
                MAPPER,
                Clock.fixed(Instant.parse("2026-08-21T03:00:00Z"), ZoneOffset.UTC));
    }

    private Map<String, AcceptanceProperties.Profile> profile(
            List<String> command, Duration timeout, List<String> requiredArtifacts) {
        return Map.of(PROFILE_ID,
                new AcceptanceProperties.Profile(command, timeout, requiredArtifacts, 4096));
    }

    private List<String> javaCommand(String... arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(testClassesDirectory().toString());
        command.add(TestCommand.class.getName());
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private Path testClassesDirectory() {
        try {
            return Path.of(TestCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Test command location is not a valid local path", exception);
        }
    }

    private SessionContext context(Path workspace) {
        NodeCommand.StartRun source = start();
        NodeCommand.StartRun command = new NodeCommand.StartRun(
                source.messageId(), source.commandId(), source.nodeId(), source.targetNodeId(), source.sentAt(),
                source.binding(), source.missionRevision(), source.objective(), source.acceptanceSummary(),
                source.authorizedSideEffectsSummary(), source.workspaceRef(), source.contextRefs(), "ws", PROFILE_ID,
                source.payload());
        return new SessionContext(command, workspace, evidenceDirectory());
    }

    private SessionHandle session(Path workspace) {
        return new SessionHandle("ses_test", "http://127.0.0.1:4096/", "test", workspace.toString());
    }

    private Path evidenceDirectory() {
        return temporaryDirectory.resolve("evidence");
    }

    public static final class TestCommand {
        private TestCommand() {
        }

        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "pass" -> System.out.print("validation passed");
                case "fail" -> System.exit(7);
                case "sleep" -> Thread.sleep(10_000);
                default -> System.exit(8);
            }
        }
    }
}
