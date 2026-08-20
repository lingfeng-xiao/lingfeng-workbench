package io.github.lingfeng.workbench.node.runtime.session;

import io.github.lingfeng.workbench.node.protocol.v2.NodeCommand;

public record InteractionInput(
        String responseCommandId,
        String interactionId,
        String checkpointId,
        NodeCommand.Decision decision,
        String responseSummary) {
}
