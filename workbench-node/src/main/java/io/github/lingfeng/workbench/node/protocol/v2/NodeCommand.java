package io.github.lingfeng.workbench.node.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public sealed interface NodeCommand permits NodeCommand.StartRun, NodeCommand.InteractionResponse, NodeCommand.CancelRun {

    String messageId();

    String commandId();

    String nodeId();

    String targetNodeId();

    Instant sentAt();

    RunBinding binding();

    JsonNode payload();

    record StartRun(
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding,
            int missionRevision,
            String objective,
            String acceptanceSummary,
            String authorizedSideEffectsSummary,
            String workspaceRef,
            List<String> contextRefs,
            String runtimeKind,
            String executionProfile,
            JsonNode payload) implements NodeCommand {

        public StartRun {
            contextRefs = contextRefs == null ? List.of() : List.copyOf(contextRefs);
        }
    }

    record InteractionResponse(
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding,
            String interactionId,
            String checkpointId,
            Decision decision,
            String responseSummary,
            Instant resolvedAt,
            JsonNode payload) implements NodeCommand {
    }

    record CancelRun(
            String messageId,
            String commandId,
            String nodeId,
            String targetNodeId,
            Instant sentAt,
            RunBinding binding,
            String reasonSummary,
            JsonNode payload) implements NodeCommand {
    }

    enum Decision {
        APPROVE,
        REJECT,
        PROVIDE_INPUT
    }
}
