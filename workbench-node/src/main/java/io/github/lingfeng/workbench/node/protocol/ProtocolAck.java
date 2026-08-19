package io.github.lingfeng.workbench.node.protocol;

public record ProtocolAck(String requestMessageId, boolean duplicate) {
}
