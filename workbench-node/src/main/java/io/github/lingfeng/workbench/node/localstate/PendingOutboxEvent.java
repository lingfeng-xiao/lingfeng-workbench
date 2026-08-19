package io.github.lingfeng.workbench.node.localstate;

import io.github.lingfeng.workbench.node.protocol.OutboundEvent;

public record PendingOutboxEvent(long sequence, OutboundEvent outboundEvent) {
}
