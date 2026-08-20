package io.github.lingfeng.workbench.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolValidation;

public final class V2TestCommands {

    public static final String DIGEST = "a".repeat(64);
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private V2TestCommands() {
    }

    public static NodeCommand.StartRun start() {
        return (NodeCommand.StartRun) ProtocolValidation.parseCommand(startPayload(), "node_alpha");
    }

    public static JsonNode startPayload() {
        return read("""
                {"protocolVersion":"2.0","messageId":"msg_cmd_start_1","nodeId":"node_alpha",
                "sentAt":"2026-08-20T01:00:00Z","commandAvailable":true,"commandType":"START_RUN",
                "commandId":"cmd_start_1","targetNodeId":"node_alpha","workItemId":"wi_001",
                "missionId":"mi_001","runId":"run_001","missionDigest":"%s","missionRevision":1,
                "objective":"Implement the frozen control loop","acceptanceSummary":"All local gates pass",
                "authorizedSideEffectsSummary":"Local code and tests only","workspaceRef":"workspace_main",
                "runtimeKind":"fake-session","executionProfile":"spm-change-v1"}
                """.formatted(DIGEST));
    }

    public static NodeCommand.InteractionResponse response() {
        return response("cp_001", DIGEST, "node_alpha", "cmd_response_1");
    }

    public static NodeCommand.InteractionResponse response(
            String checkpointId, String digest, String targetNodeId, String commandId) {
        JsonNode payload = read("""
                {"protocolVersion":"2.0","messageId":"msg_response","nodeId":"node_alpha",
                "sentAt":"2026-08-20T01:10:00Z","commandAvailable":true,
                "commandType":"PROVIDE_INTERACTION_RESPONSE","commandId":"%s","targetNodeId":"%s",
                "workItemId":"wi_001","missionId":"mi_001","runId":"run_001","missionDigest":"%s",
                "interactionId":"int_001","checkpointId":"%s","decision":"APPROVE",
                "responseSummary":"Approved for the frozen local scope","resolvedAt":"2026-08-20T01:09:00Z"}
                """.formatted(commandId, targetNodeId, digest, checkpointId));
        return (NodeCommand.InteractionResponse) ProtocolValidation.parseCommand(payload, "node_alpha");
    }

    public static NodeCommand.CancelRun cancel() {
        JsonNode payload = read("""
                {"protocolVersion":"2.0","messageId":"msg_cancel","nodeId":"node_alpha",
                "sentAt":"2026-08-20T01:20:00Z","commandAvailable":true,"commandType":"CANCEL_RUN",
                "commandId":"cmd_cancel_1","targetNodeId":"node_alpha","workItemId":"wi_001",
                "missionId":"mi_001","runId":"run_001","missionDigest":"%s",
                "reasonSummary":"Cancelled by an authorized client"}
                """.formatted(DIGEST));
        return (NodeCommand.CancelRun) ProtocolValidation.parseCommand(payload, "node_alpha");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
