package io.github.lingfeng.workbench.node.localstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.protocol.Assignment;
import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class LocalNodeStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS local_run (
                run_id TEXT PRIMARY KEY,
                command_id TEXT NOT NULL UNIQUE,
                mission_id TEXT NOT NULL,
                mission_digest TEXT NOT NULL,
                runtime_kind TEXT NOT NULL,
                state TEXT NOT NULL,
                resumable INTEGER NOT NULL DEFAULT 0,
                runtime_session_ref TEXT,
                evidence_directory TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS outbox_event (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL UNIQUE,
                run_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (run_id) REFERENCES local_run(run_id)
            );
            """;

    private final Path stateDirectory;
    private final Path runsDirectory;
    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    public LocalNodeStore(Path stateDirectory, ObjectMapper objectMapper) {
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.runsDirectory = this.stateDirectory.resolve("runs");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(runsDirectory);
        } catch (IOException exception) {
            throw new LocalStateException("Unable to create node state directory", exception);
        }
        this.jdbcUrl = "jdbc:sqlite:" + this.stateDirectory.resolve("node.db");
        initializeSchema();
    }

    public RunRegistration registerAssignment(Assignment assignment) {
        Path evidenceDirectory = safeRunDirectory(assignment.runId());
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                ExistingRun existingRun = findRun(connection, assignment.runId());
                if (existingRun != null) {
                    verifyExistingRun(existingRun, assignment);
                    connection.commit();
                    RunRegistration.Status status = existingRun.terminal()
                            ? RunRegistration.Status.EXISTING_TERMINAL
                            : RunRegistration.Status.EXISTING_ACTIVE;
                    return new RunRegistration(status, Path.of(existingRun.evidenceDirectory()));
                }
                Files.createDirectories(evidenceDirectory);
                writeMissionSnapshot(evidenceDirectory, assignment);
                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO local_run (
                            run_id, command_id, mission_id, mission_digest, runtime_kind,
                            state, evidence_directory, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'ASSIGNED', ?, ?, ?)
                        """)) {
                    insert.setString(1, assignment.runId());
                    insert.setString(2, assignment.commandId());
                    insert.setString(3, assignment.missionId());
                    insert.setString(4, assignment.missionDigest());
                    insert.setString(5, assignment.runtimeKind());
                    insert.setString(6, evidenceDirectory.toString());
                    insert.setString(7, now);
                    insert.setString(8, now);
                    insert.executeUpdate();
                }
                connection.commit();
                return new RunRegistration(RunRegistration.Status.NEW, evidenceDirectory);
            } catch (SQLException | IOException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | IOException exception) {
            throw new LocalStateException("Unable to register assignment " + assignment.runId(), exception);
        }
    }

    public void enqueueEvent(OutboundEvent event, String localState, boolean resumable, String runtimeSessionRef) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE local_run
                        SET state = ?, resumable = ?, runtime_session_ref = COALESCE(?, runtime_session_ref), updated_at = ?
                        WHERE run_id = ?
                        """)) {
                    update.setString(1, localState);
                    update.setInt(2, resumable ? 1 : 0);
                    update.setString(3, runtimeSessionRef);
                    update.setString(4, now);
                    update.setString(5, event.runId());
                    if (update.executeUpdate() != 1) {
                        throw new LocalStateException("Local run does not exist: " + event.runId());
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO outbox_event (
                            message_id, run_id, event_type, payload_json, created_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, event.messageId());
                    insert.setString(2, event.runId());
                    insert.setString(3, event.eventType());
                    insert.setString(4, objectMapper.writeValueAsString(event.payload()));
                    insert.setString(5, now);
                    insert.executeUpdate();
                }
                appendNormalizedEvent(event);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | IOException exception) {
            throw new LocalStateException("Unable to enqueue event " + event.messageId(), exception);
        }
    }

    public List<PendingOutboxEvent> pendingEvents(int limit) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT sequence, message_id, run_id, event_type, payload_json
                FROM outbox_event ORDER BY sequence LIMIT ?
                """)) {
            query.setInt(1, limit);
            try (ResultSet rows = query.executeQuery()) {
                List<PendingOutboxEvent> events = new ArrayList<>();
                while (rows.next()) {
                    JsonNode payload = objectMapper.readTree(rows.getString("payload_json"));
                    OutboundEvent event = new OutboundEvent(
                            rows.getString("message_id"),
                            rows.getString("run_id"),
                            rows.getString("event_type"),
                            payload);
                    events.add(new PendingOutboxEvent(rows.getLong("sequence"), event));
                }
                return List.copyOf(events);
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new LocalStateException("Unable to read pending outbox events", exception);
        }
    }

    public void acknowledgeEvent(String messageId) {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM outbox_event WHERE message_id = ?")) {
            delete.setString(1, messageId);
            delete.executeUpdate();
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to acknowledge outbox event " + messageId, exception);
        }
    }

    public List<String> acknowledgedCommandIds() {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT command_id FROM local_run ORDER BY created_at DESC LIMIT 100");
                ResultSet rows = query.executeQuery()) {
            List<String> commandIds = new ArrayList<>();
            while (rows.next()) {
                commandIds.add(rows.getString(1));
            }
            return List.copyOf(commandIds);
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to read acknowledged command IDs", exception);
        }
    }

    private void initializeSchema() {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("PRAGMA synchronous=FULL");
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            for (String sql : SCHEMA.split(";")) {
                if (!sql.isBlank()) {
                    statement.executeUpdate(sql);
                }
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to initialize local node database", exception);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA busy_timeout=5000");
            statement.executeUpdate("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private Path safeRunDirectory(String runId) {
        Path runDirectory = runsDirectory.resolve(runId).normalize();
        if (!runDirectory.startsWith(runsDirectory)) {
            throw new LocalStateException("Run identifier escapes the evidence directory");
        }
        return runDirectory;
    }

    private void writeMissionSnapshot(Path evidenceDirectory, Assignment assignment) throws IOException {
        byte[] snapshot = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(assignment);
        Files.write(evidenceDirectory.resolve("mission.json"), snapshot, StandardOpenOption.CREATE_NEW);
        createEvidenceFile(evidenceDirectory, "normalized-events.ndjson");
        createEvidenceFile(evidenceDirectory, "runtime-events.ndjson");
        createEvidenceFile(evidenceDirectory, "runtime-stderr.log");
        createEvidenceFile(evidenceDirectory, "result.md");
    }

    private static void createEvidenceFile(Path evidenceDirectory, String fileName) throws IOException {
        Files.write(evidenceDirectory.resolve(fileName), new byte[0], StandardOpenOption.CREATE_NEW);
    }

    private void appendNormalizedEvent(OutboundEvent event) throws IOException {
        Path runDirectory = safeRunDirectory(event.runId());
        byte[] line = (objectMapper.writeValueAsString(event.payload()) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        Files.write(runDirectory.resolve("normalized-events.ndjson"), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static ExistingRun findRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT command_id, mission_id, mission_digest, state, evidence_directory
                FROM local_run WHERE run_id = ?
                """)) {
            query.setString(1, runId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new ExistingRun(
                        row.getString("command_id"),
                        row.getString("mission_id"),
                        row.getString("mission_digest"),
                        row.getString("state"),
                        row.getString("evidence_directory"));
            }
        }
    }

    private static void verifyExistingRun(ExistingRun existingRun, Assignment assignment) {
        boolean sameIdentity = existingRun.commandId().equals(assignment.commandId())
                && existingRun.missionId().equals(assignment.missionId())
                && existingRun.missionDigest().equals(assignment.missionDigest());
        if (!sameIdentity) {
            throw new LocalStateException("Assignment conflicts with existing local run " + assignment.runId());
        }
    }

    private static void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private record ExistingRun(
            String commandId,
            String missionId,
            String missionDigest,
            String state,
            String evidenceDirectory) {

        boolean terminal() {
            return state.equals("FINISHED") || state.equals("FAILED") || state.equals("INTERRUPTED");
        }
    }
}
