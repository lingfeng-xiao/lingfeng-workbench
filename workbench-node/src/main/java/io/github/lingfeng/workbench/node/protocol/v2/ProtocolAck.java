package io.github.lingfeng.workbench.node.protocol.v2;

public record ProtocolAck(String requestMessageId, boolean duplicate) {
}
