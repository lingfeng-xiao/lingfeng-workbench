package io.github.lingfeng.workbench.node.localstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lingfeng.workbench.node.evidence.BoundedEvidenceWriter;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.protocol.v2.RunBinding;
import io.github.lingfeng.workbench.node.runtime.session.NormalizedRuntimeEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ControlLoopStore {

    private static final Set<String> TERMINAL_STATES = Set.of("completed", "failed", "interrupted", "uncertain");
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS control_received_command (
                command_id TEXT PRIMARY KEY,
                payload_digest TEXT NOT NULL,
                command_type TEXT NOT NULL,
                run_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS control_local_run (
                run_id TEXT PRIMARY KEY,
                work_item_id TEXT NOT NULL,
                mission_id TEXT NOT NULL,
                mission_digest TEXT NOT NULL,
                start_command_json TEXT NOT NULL,
                state TEXT NOT NULL,
                next_sequence INTEGER NOT NULL,
                evidence_directory TEXT NOT NULL,
                current_turn INTEGER NOT NULL DEFAULT 0,
                terminal_sequence INTEGER,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS control_agent_session (
                run_id TEXT PRIMARY KEY,
                handle_ref TEXT NOT NULL,
                state TEXT NOT NULL,
                resumable INTEGER NOT NULL,
                checkpoint_id TEXT,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS control_turn (
                run_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                state TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (run_id, turn_id)
            );
            CREATE TABLE IF NOT EXISTS control_interaction_binding (
                interaction_id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL,
                checkpoint_id TEXT NOT NULL,
                mission_digest TEXT NOT NULL,
                target_node_id TEXT NOT NULL,
                response_state TEXT NOT NULL,
                response_command_id TEXT,
                response_json TEXT,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS control_local_event (
                run_id TEXT NOT NULL,
                local_sequence INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (run_id, local_sequence)
            );
            CREATE TABLE IF NOT EXISTS control_outbox (
                outbox_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL UNIQUE,
                run_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                payload_digest TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """;

    private final Path stateDirectory;
    private final Path runsDirectory;
    private final String jdbcUrl;
    private final String nodeId;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ControlLoopStore(
            Path stateDirectory, String nodeId, ObjectMapper objectMapper, Clock clock) {
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.runsDirectory = this.stateDirectory.resolve("runs");
        this.jdbcUrl = "jdbc:sqlite:" + this.stateDirectory.resolve("node.db");
        this.nodeId = nodeId;
        this.objectMapper = objectMapper;
        this.clock = clock;
        try {
            Files.createDirectories(runsDirectory);
        } catch (IOException exception) {
            throw new LocalStateException("Unable to create Node state directory", exception);
        }
        initializeSchema();
    }

    public synchronized StoredCommand storeCommand(NodeCommand command) {
        String payloadDigest = payloadDigest(command.payload());
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                StoredCommand existing = findCommand(connection, command.commandId());
                if (existing != null) {
                    if (!existing.payloadDigest().equals(payloadDigest)) {
                        throw new LocalStateException("Command ID conflicts with a different durable payload");
                    }
                    connection.commit();
                    return new StoredCommand(StoreStatus.DUPLICATE, payloadDigest);
                }
                validateCommandAgainstState(connection, command);
                if (command instanceof NodeCommand.StartRun start) {
                    createRun(connection, start);
                }
                insertCommand(connection, command, payloadDigest);
                if (command instanceof NodeCommand.InteractionResponse response) {
                    storeInteractionResponse(connection, response);
                }
                appendCommandStored(connection, command.binding(), command.commandId(), payloadDigest);
                appendControlCommandEvidence(command);
                connection.commit();
                return new StoredCommand(StoreStatus.NEW, payloadDigest);
            } catch (SQLException | IOException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException | IOException exception) {
            throw new LocalStateException("Unable to store command " + command.commandId(), exception);
        }
    }

    public synchronized void markOpeningSession(String runId) {
        updateRunState(runId, "opening_session");
    }

    public synchronized void saveSession(String runId, String handleReference, boolean resumable) {
        try (Connection connection = connect()) {
            String now = now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO control_agent_session (run_id, handle_ref, state, resumable, updated_at)
                    VALUES (?, ?, 'open', ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET handle_ref=excluded.handle_ref, state='open',
                        resumable=excluded.resumable, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, runId);
                statement.setString(2, handleReference);
                statement.setInt(3, resumable ? 1 : 0);
                statement.setString(4, now);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to save Agent Session", exception);
        }
    }

    public synchronized void recordRunStarted(String runId, boolean resumable) {
        appendProjectedEvent(runId, "RUN_STARTED", event -> event.put("resumable", resumable), "running");
    }

    public synchronized void recordPhase(String runId, String phaseCode, String summary) {
        if (!Set.of("CONTRACT_REVIEW", "CONTEXT_FREEZE", "IMPLEMENTATION", "BUILD_VALIDATION",
                "API_VALIDATION", "REPORTING").contains(phaseCode)) {
            throw new LocalStateException("Runtime emitted an unsupported phaseCode");
        }
        appendProjectedEvent(runId, "PHASE_CHANGED", event -> {
            event.put("phaseCode", phaseCode);
            event.put("phaseSummary", compactSummary(summary));
        }, null);
    }

    public synchronized void recordProgress(String runId, String summary) {
        appendProjectedEvent(runId, "PROGRESS_UPDATED",
                event -> event.put("progressSummary", compactSummary(summary)), null);
    }

    public synchronized void recordTurn(String runId, String turnId, int turnNumber, String state) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO control_turn (run_id, turn_id, turn_number, state, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(run_id, turn_id) DO UPDATE SET state=excluded.state, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, runId);
            statement.setString(2, turnId);
            statement.setInt(3, turnNumber);
            statement.setString(4, state);
            statement.setString(5, now());
            statement.executeUpdate();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE control_local_run SET current_turn=MAX(current_turn, ?), updated_at=? WHERE run_id=?")) {
                update.setInt(1, turnNumber);
                update.setString(2, now());
                update.setString(3, runId);
                update.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to record Turn", exception);
        }
    }

    public synchronized void recordInteraction(
            String runId,
            String interactionId,
            String checkpointId,
            String promptSummary,
            Set<String> allowedDecisions,
            boolean resumable) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                RunRow run = requireRun(connection, runId);
                String now = now();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO control_interaction_binding (
                            interaction_id, run_id, checkpoint_id, mission_digest, target_node_id,
                            response_state, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'pending', ?)
                        ON CONFLICT(interaction_id) DO NOTHING
                        """)) {
                    insert.setString(1, interactionId);
                    insert.setString(2, runId);
                    insert.setString(3, checkpointId);
                    insert.setString(4, run.binding().missionDigest());
                    insert.setString(5, nodeId);
                    insert.setString(6, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement session = connection.prepareStatement("""
                        UPDATE control_agent_session SET state='paused', checkpoint_id=?, updated_at=? WHERE run_id=?
                        """)) {
                    session.setString(1, checkpointId);
                    session.setString(2, now);
                    session.setString(3, runId);
                    session.executeUpdate();
                }
                appendEvent(connection, run, "INTERACTION_REQUESTED", event -> {
                    event.put("interactionId", interactionId);
                    event.put("checkpointId", checkpointId);
                    event.put("targetNodeId", nodeId);
                    event.put("promptSummary", compactSummary(promptSummary));
                    ArrayNode decisions = event.putArray("allowedDecisions");
                    allowedDecisions.stream().sorted().forEach(decisions::add);
                    event.put("resumable", resumable);
                });
                setRunState(connection, runId, "waiting_interaction");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to record Interaction", exception);
        }
    }

    public synchronized boolean recordInteractionConsumed(NodeCommand.InteractionResponse response) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE control_interaction_binding SET response_state='consumed', updated_at=?
                        WHERE interaction_id=? AND response_command_id=? AND response_state='stored'
                        """)) {
                    update.setString(1, now());
                    update.setString(2, response.interactionId());
                    update.setString(3, response.commandId());
                    if (update.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                RunRow run = requireRun(connection, response.binding().runId());
                appendEvent(connection, run, "INTERACTION_RESPONSE_CONSUMED", event -> {
                    event.put("interactionId", response.interactionId());
                    event.put("checkpointId", response.checkpointId());
                    event.put("targetNodeId", nodeId);
                    event.put("responseCommandId", response.commandId());
                });
                setRunState(connection, response.binding().runId(), "running");
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to record consumed Interaction", exception);
        }
    }

    public synchronized boolean tryRecordTerminal(
            String runId,
            NormalizedRuntimeEvent.RuntimeOutcome runtimeOutcome,
            NormalizedRuntimeEvent.AcceptanceStatus acceptanceStatus,
            String summary) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                RunRow run = requireRun(connection, runId);
                if (TERMINAL_STATES.contains(run.state())) {
                    connection.rollback();
                    return false;
                }
                long sequence = appendEvent(connection, run, "RUN_TERMINAL", event -> {
                    event.put("runtimeOutcome", runtimeOutcome.name());
                    event.put("acceptanceStatus", acceptanceStatus.name());
                    event.put("resultSummary", compactSummary(summary));
                    event.put("resumable", false);
                });
                String terminalState = terminalState(runtimeOutcome, acceptanceStatus);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE control_local_run SET state=?, terminal_sequence=?, updated_at=? WHERE run_id=?
                        """)) {
                    update.setString(1, terminalState);
                    update.setLong(2, sequence);
                    update.setString(3, now());
                    update.setString(4, runId);
                    update.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to record Run terminal", exception);
        }
    }

    public List<DurableNodeEvent> pendingEvents(int limit) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT outbox_sequence, message_id, run_id, event_type, payload_json
                FROM control_outbox ORDER BY outbox_sequence LIMIT ?
                """)) {
            query.setInt(1, limit);
            try (ResultSet rows = query.executeQuery()) {
                List<DurableNodeEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(new DurableNodeEvent(
                            rows.getLong("outbox_sequence"), rows.getString("message_id"),
                            rows.getString("run_id"), rows.getString("event_type"),
                            objectMapper.readTree(rows.getString("payload_json"))));
                }
                return List.copyOf(events);
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new LocalStateException("Unable to read control outbox", exception);
        }
    }

    public synchronized void acknowledgeEvent(String messageId) {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM control_outbox WHERE message_id=?")) {
            delete.setString(1, messageId);
            delete.executeUpdate();
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to acknowledge control event", exception);
        }
    }

    public Optional<RecoveryRun> activeRun() {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT r.run_id, r.start_command_json, r.state, r.current_turn, r.evidence_directory,
                       s.handle_ref, s.resumable, s.checkpoint_id
                FROM control_local_run r LEFT JOIN control_agent_session s ON s.run_id=r.run_id
                WHERE r.state NOT IN ('completed','failed','interrupted','uncertain')
                ORDER BY r.updated_at DESC LIMIT 1
                """)) {
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                JsonNode commandJson = objectMapper.readTree(row.getString("start_command_json"));
                NodeCommand.StartRun command = (NodeCommand.StartRun) io.github.lingfeng.workbench.node.protocol.v2
                        .ProtocolValidation.parseCommand(commandJson, nodeId);
                return Optional.of(new RecoveryRun(
                        command, row.getString("state"), row.getInt("current_turn"),
                        row.getString("handle_ref"), row.getInt("resumable") == 1,
                        row.getString("checkpoint_id"), Path.of(row.getString("evidence_directory"))));
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new LocalStateException("Unable to recover active Run", exception);
        }
    }

    public String activeRunId() {
        return activeRun().map(recovery -> recovery.command().binding().runId()).orElse(null);
    }

    public String activeRunState() {
        return activeRun().map(RecoveryRun::state).orElse(null);
    }

    public Optional<NodeCommand.InteractionResponse> storedInteractionResponse(String runId) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT response_json FROM control_interaction_binding
                WHERE run_id=? AND response_state='stored' AND response_json IS NOT NULL LIMIT 1
                """)) {
            query.setString(1, runId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                JsonNode payload = objectMapper.readTree(row.getString(1));
                return Optional.of((NodeCommand.InteractionResponse)
                        io.github.lingfeng.workbench.node.protocol.v2.ProtocolValidation.parseCommand(payload, nodeId));
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new LocalStateException("Unable to recover stored Interaction response", exception);
        }
    }

    public Path evidenceDirectory(String runId) {
        try (Connection connection = connect()) {
            return Path.of(requireRun(connection, runId).evidenceDirectory());
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to find evidence directory", exception);
        }
    }

    private void validateCommandAgainstState(Connection connection, NodeCommand command) throws SQLException {
        if (command instanceof NodeCommand.StartRun start) {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT run_id FROM control_local_run
                    WHERE state NOT IN ('completed','failed','interrupted','uncertain') AND run_id<>? LIMIT 1
                    """)) {
                query.setString(1, start.binding().runId());
                try (ResultSet active = query.executeQuery()) {
                    if (active.next()) {
                        throw new NodeBusyException("Node already has an active Run");
                    }
                }
            }
            return;
        }
        RunRow run = requireRun(connection, command.binding().runId());
        requireBinding(run.binding(), command.binding());
        if (command instanceof NodeCommand.InteractionResponse response) {
            if (!run.state().equals("waiting_interaction")) {
                throw new LocalStateException("Run is not waiting for Interaction");
            }
            requireInteractionBinding(connection, response);
        }
    }

    private void requireInteractionBinding(Connection connection, NodeCommand.InteractionResponse response)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT run_id, checkpoint_id, mission_digest, target_node_id, response_state
                FROM control_interaction_binding WHERE interaction_id=?
                """)) {
            query.setString(1, response.interactionId());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()
                        || !row.getString("run_id").equals(response.binding().runId())
                        || !row.getString("checkpoint_id").equals(response.checkpointId())
                        || !row.getString("mission_digest").equals(response.binding().missionDigest())
                        || !row.getString("target_node_id").equals(response.targetNodeId())
                        || !row.getString("response_state").equals("pending")) {
                    throw new LocalStateException("Interaction response binding or state conflicts with local state");
                }
            }
        }
    }

    private void createRun(Connection connection, NodeCommand.StartRun command) throws SQLException, IOException {
        Path evidenceDirectory = safeRunDirectory(command.binding().runId());
        Files.createDirectories(evidenceDirectory);
        createEvidenceFiles(evidenceDirectory);
        Files.write(evidenceDirectory.resolve("mission.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(command.payload()),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO control_local_run (
                    run_id, work_item_id, mission_id, mission_digest, start_command_json,
                    state, next_sequence, evidence_directory, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'received', 1, ?, ?)
                """)) {
            insert.setString(1, command.binding().runId());
            insert.setString(2, command.binding().workItemId());
            insert.setString(3, command.binding().missionId());
            insert.setString(4, command.binding().missionDigest());
            insert.setString(5, objectMapper.writeValueAsString(command.payload()));
            insert.setString(6, evidenceDirectory.toString());
            insert.setString(7, now());
            insert.executeUpdate();
        }
    }

    private void insertCommand(Connection connection, NodeCommand command, String payloadDigest)
            throws SQLException, JsonProcessingException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO control_received_command (
                    command_id, payload_digest, command_type, run_id, payload_json, state, created_at
                ) VALUES (?, ?, ?, ?, ?, 'stored', ?)
                """)) {
            insert.setString(1, command.commandId());
            insert.setString(2, payloadDigest);
            insert.setString(3, command.payload().path("commandType").asText());
            insert.setString(4, command.binding().runId());
            insert.setString(5, objectMapper.writeValueAsString(command.payload()));
            insert.setString(6, now());
            insert.executeUpdate();
        }
    }

    private void storeInteractionResponse(Connection connection, NodeCommand.InteractionResponse response)
            throws SQLException, JsonProcessingException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE control_interaction_binding
                SET response_state='stored', response_command_id=?, response_json=?, updated_at=?
                WHERE interaction_id=? AND response_state='pending'
                """)) {
            update.setString(1, response.commandId());
            update.setString(2, objectMapper.writeValueAsString(response.payload()));
            update.setString(3, now());
            update.setString(4, response.interactionId());
            if (update.executeUpdate() != 1) {
                throw new LocalStateException("Interaction response was already stored");
            }
        }
    }

    private void appendCommandStored(
            Connection connection, RunBinding binding, String commandId, String commandPayloadDigest)
            throws SQLException {
        RunRow run = requireRun(connection, binding.runId());
        appendEvent(connection, run, "COMMAND_STORED", event -> {
            event.put("commandId", commandId);
            event.put("commandPayloadDigest", commandPayloadDigest);
        });
    }

    private void appendProjectedEvent(
            String runId, String eventType, EventFields fields, String newState) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                RunRow run = requireRun(connection, runId);
                if (!TERMINAL_STATES.contains(run.state())) {
                    appendEvent(connection, run, eventType, fields);
                    if (newState != null) {
                        setRunState(connection, runId, newState);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to append projected event", exception);
        }
    }

    private long appendEvent(Connection connection, RunRow run, String eventType, EventFields fields)
            throws SQLException {
        long localSequence = run.nextSequence();
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocolVersion", "2.0");
        event.put("messageId", "evt-" + UUID.randomUUID());
        event.put("nodeId", nodeId);
        event.put("sentAt", now());
        event.put("eventType", eventType);
        event.put("localSequence", localSequence);
        event.put("workItemId", run.binding().workItemId());
        event.put("missionId", run.binding().missionId());
        event.put("runId", run.binding().runId());
        event.put("missionDigest", run.binding().missionDigest());
        fields.addTo(event);
        SensitivePayloadGuard.requireControlProjection(event, stateDirectory);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new LocalStateException("Unable to encode local event", exception);
        }
        String createdAt = now();
        try (PreparedStatement localInsert = connection.prepareStatement("""
                INSERT INTO control_local_event (run_id, local_sequence, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            localInsert.setString(1, run.binding().runId());
            localInsert.setLong(2, localSequence);
            localInsert.setString(3, eventType);
            localInsert.setString(4, payloadJson);
            localInsert.setString(5, createdAt);
            localInsert.executeUpdate();
        }
        try (PreparedStatement outboxInsert = connection.prepareStatement("""
                INSERT INTO control_outbox (
                    message_id, run_id, event_type, payload_digest, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            outboxInsert.setString(1, event.path("messageId").asText());
            outboxInsert.setString(2, run.binding().runId());
            outboxInsert.setString(3, eventType);
            outboxInsert.setString(4, payloadDigest(event));
            outboxInsert.setString(5, payloadJson);
            outboxInsert.setString(6, createdAt);
            outboxInsert.executeUpdate();
        }
        try (PreparedStatement sequenceUpdate = connection.prepareStatement(
                "UPDATE control_local_run SET next_sequence=?, updated_at=? WHERE run_id=?")) {
            sequenceUpdate.setLong(1, localSequence + 1);
            sequenceUpdate.setString(2, createdAt);
            sequenceUpdate.setString(3, run.binding().runId());
            sequenceUpdate.executeUpdate();
        }
        appendNormalizedEvidence(run.binding().runId(), payloadJson);
        return localSequence;
    }

    private void updateRunState(String runId, String state) {
        try (Connection connection = connect()) {
            setRunState(connection, runId, state);
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to update Run state", exception);
        }
    }

    private void setRunState(Connection connection, String runId, String state) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE control_local_run SET state=?, updated_at=? WHERE run_id=?")) {
            update.setString(1, state);
            update.setString(2, now());
            update.setString(3, runId);
            if (update.executeUpdate() != 1) {
                throw new LocalStateException("Local Run does not exist");
            }
        }
    }

    private RunRow requireRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT work_item_id, mission_id, mission_digest, state, next_sequence, evidence_directory
                FROM control_local_run WHERE run_id=?
                """)) {
            query.setString(1, runId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new LocalStateException("Local Run does not exist: " + runId);
                }
                return new RunRow(
                        new RunBinding(row.getString("work_item_id"), row.getString("mission_id"), runId,
                                row.getString("mission_digest")),
                        row.getString("state"), row.getLong("next_sequence"),
                        row.getString("evidence_directory"));
            }
        }
    }

    private StoredCommand findCommand(Connection connection, String commandId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT payload_digest FROM control_received_command WHERE command_id=?")) {
            query.setString(1, commandId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new StoredCommand(StoreStatus.DUPLICATE, row.getString(1)) : null;
            }
        }
    }

    private void appendControlCommandEvidence(NodeCommand command) throws IOException {
        BoundedEvidenceWriter.appendLine(
                safeRunDirectory(command.binding().runId()).resolve("control-commands.ndjson"),
                objectMapper.writeValueAsString(command.payload()));
    }

    private void appendNormalizedEvidence(String runId, String payloadJson) {
        try {
            BoundedEvidenceWriter.appendLine(
                    safeRunDirectory(runId).resolve("normalized-events.ndjson"), payloadJson);
        } catch (IOException exception) {
            throw new LocalStateException("Unable to preserve normalized event evidence", exception);
        }
    }

    private void createEvidenceFiles(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("checkpoints"));
        for (String name : List.of("control-commands.ndjson", "normalized-events.ndjson", "runtime-events.ndjson",
                "runtime-stderr.log", "conversation.ndjson", "result.md")) {
            Path file = directory.resolve(name);
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        }
    }

    private Path safeRunDirectory(String runId) {
        Path directory = runsDirectory.resolve(runId).normalize();
        if (!directory.startsWith(runsDirectory)) {
            throw new LocalStateException("Run ID escapes the evidence directory");
        }
        return directory;
    }

    private void initializeSchema() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("PRAGMA synchronous=FULL");
            for (String sql : SCHEMA.split(";")) {
                if (!sql.isBlank()) {
                    statement.executeUpdate(sql);
                }
            }
        } catch (SQLException exception) {
            throw new LocalStateException("Unable to initialize v2 control-loop schema", exception);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private String payloadDigest(JsonNode payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new LocalStateException("Unable to digest protocol payload", exception);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static void requireBinding(RunBinding expected, RunBinding actual) {
        if (!expected.equals(actual)) {
            throw new LocalStateException("Run binding conflicts with local durable state");
        }
    }

    private static String compactSummary(String source) {
        String compact = source == null ? "" : source.trim().replaceAll("\\s+", " ");
        if (compact.isEmpty()) {
            compact = "No summary was provided";
        }
        return compact.length() <= 800 ? compact : compact.substring(0, 797) + "...";
    }

    private static String terminalState(
            NormalizedRuntimeEvent.RuntimeOutcome runtimeOutcome,
            NormalizedRuntimeEvent.AcceptanceStatus acceptanceStatus) {
        if (runtimeOutcome == NormalizedRuntimeEvent.RuntimeOutcome.SUCCEEDED
                && acceptanceStatus == NormalizedRuntimeEvent.AcceptanceStatus.PASSED) {
            return "completed";
        }
        return switch (runtimeOutcome) {
            case FAILED -> "failed";
            case INTERRUPTED -> "interrupted";
            case UNKNOWN, SUCCEEDED -> "uncertain";
        };
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface EventFields {
        void addTo(ObjectNode event);
    }

    private record RunRow(RunBinding binding, String state, long nextSequence, String evidenceDirectory) {
    }

    public record StoredCommand(StoreStatus status, String payloadDigest) {
    }

    public enum StoreStatus {
        NEW,
        DUPLICATE
    }

    public record RecoveryRun(
            NodeCommand.StartRun command,
            String state,
            int currentTurn,
            String sessionHandle,
            boolean resumable,
            String checkpointId,
            Path evidenceDirectory) {
    }
}
