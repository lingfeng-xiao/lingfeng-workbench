package io.github.lingfeng.workbench.node.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class BoundedEvidenceWriter {

    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    private BoundedEvidenceWriter() {
    }

    public static synchronized void appendLine(Path destination, String line) throws IOException {
        byte[] encoded = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > DEFAULT_MAX_BYTES) {
            throw new IOException("A single evidence record exceeds the bounded log size");
        }
        if (Files.exists(destination) && Files.size(destination) + encoded.length > DEFAULT_MAX_BYTES) {
            Path rotated = destination.resolveSibling(destination.getFileName() + ".1");
            Files.move(destination, rotated, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(destination, encoded, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
