package io.github.lingfeng.workbench.node.protocol.v2;

import static io.github.lingfeng.workbench.node.V2TestCommands.MAPPER;
import static io.github.lingfeng.workbench.node.V2TestCommands.startPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lingfeng.workbench.node.connection.ProtocolClientException;
import org.junit.jupiter.api.Test;

class ProtocolValidationTest {

    @Test
    void parsesStrictV2StartCommand() {
        NodeCommand command = ProtocolValidation.parseCommand(startPayload(), "node_alpha");

        assertThat(command).isInstanceOf(NodeCommand.StartRun.class);
        assertThat(command.binding().runId()).isEqualTo("run_001");
        assertThat(((NodeCommand.StartRun) command).contextRefs()).isEmpty();
    }

    @Test
    void acceptsOptionalSafeContextRefsAndRejectsDuplicates() {
        var withContext = (com.fasterxml.jackson.databind.node.ObjectNode) startPayload().deepCopy();
        withContext.putArray("contextRefs").add("context-design").add("context-tests");
        var duplicate = (com.fasterxml.jackson.databind.node.ObjectNode) startPayload().deepCopy();
        duplicate.putArray("contextRefs").add("context-design").add("context-design");

        NodeCommand.StartRun command = (NodeCommand.StartRun)
                ProtocolValidation.parseCommand(withContext, "node_alpha");

        assertThat(command.contextRefs()).containsExactly("context-design", "context-tests");
        assertThatThrownBy(() -> ProtocolValidation.parseCommand(duplicate, "node_alpha"))
                .isInstanceOf(ProtocolClientException.class).hasMessageContaining("unique");
    }

    @Test
    void rejectsUnknownSensitiveFieldWrongNodeAndOversize() {
        var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) startPayload().deepCopy();
        unknown.put("runtimeSessionId", "forbidden");
        var wrongNode = (com.fasterxml.jackson.databind.node.ObjectNode) startPayload().deepCopy();
        wrongNode.put("targetNodeId", "node_beta");

        assertThatThrownBy(() -> ProtocolValidation.parseCommand(unknown, "node_alpha"))
                .isInstanceOf(ProtocolClientException.class).hasMessageContaining("unknown fields");
        assertThatThrownBy(() -> ProtocolValidation.parseCommand(wrongNode, "node_alpha"))
                .isInstanceOf(ProtocolClientException.class).hasMessageContaining("another Node");
        assertThatThrownBy(() -> ProtocolValidation.requireMessageSize(new byte[65 * 1024]))
                .isInstanceOf(ProtocolClientException.class).hasMessageContaining("64 KiB");
    }

    @Test
    void rejectsUnknownDecisionAndMissingCheckpoint() throws Exception {
        var response = MAPPER.readTree("""
                {"protocolVersion":"2.0","messageId":"m","nodeId":"node_alpha",
                "sentAt":"2026-08-20T01:10:00Z","commandAvailable":true,
                "commandType":"PROVIDE_INTERACTION_RESPONSE","commandId":"c","targetNodeId":"node_alpha",
                "workItemId":"wi_001","missionId":"mi_001","runId":"run_001",
                "missionDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "interactionId":"int_001","decision":"AUTO_APPROVE","responseSummary":"bad",
                "resolvedAt":"2026-08-20T01:09:00Z"}
                """);

        assertThatThrownBy(() -> ProtocolValidation.parseCommand(response, "node_alpha"))
                .isInstanceOf(ProtocolClientException.class);
    }
}
