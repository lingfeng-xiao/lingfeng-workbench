package io.github.lingfeng.workbench.node.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedEvidenceWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rotatesBoundedLogWithoutTouchingMissionOrResultEvidence() throws Exception {
        Path log = temporaryDirectory.resolve("runtime-events.ndjson");
        Path mission = temporaryDirectory.resolve("mission.json");
        Path result = temporaryDirectory.resolve("result.md");
        Files.writeString(log, "x".repeat((int) BoundedEvidenceWriter.DEFAULT_MAX_BYTES));
        Files.writeString(mission, "mission");
        Files.writeString(result, "result");

        BoundedEvidenceWriter.appendLine(log, "next");

        assertThat(log.resolveSibling("runtime-events.ndjson.1")).hasSize(
                BoundedEvidenceWriter.DEFAULT_MAX_BYTES);
        assertThat(Files.readString(log)).isEqualTo("next" + System.lineSeparator());
        assertThat(Files.readString(mission)).isEqualTo("mission");
        assertThat(Files.readString(result)).isEqualTo("result");
    }
}
