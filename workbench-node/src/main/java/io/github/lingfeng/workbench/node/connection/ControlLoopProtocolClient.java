package io.github.lingfeng.workbench.node.connection;

import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.PollResult;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolAck;
import java.util.Set;

public interface ControlLoopProtocolClient {

    ProtocolAck hello(Set<String> capabilities);

    ProtocolAck heartbeat(String activeRunId, String activeRunState);

    PollResult poll();

    ProtocolAck sendEvent(DurableNodeEvent event);
}
