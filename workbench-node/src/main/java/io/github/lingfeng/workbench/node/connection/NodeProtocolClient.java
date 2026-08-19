package io.github.lingfeng.workbench.node.connection;

import io.github.lingfeng.workbench.node.protocol.OutboundEvent;
import io.github.lingfeng.workbench.node.protocol.PollResponse;
import io.github.lingfeng.workbench.node.protocol.ProtocolAck;
import java.util.List;
import java.util.Set;

public interface NodeProtocolClient {

    ProtocolAck hello(Set<String> capabilities);

    ProtocolAck heartbeat(String activeRunId);

    PollResponse poll(List<String> acknowledgedCommandIds);

    ProtocolAck sendEvent(OutboundEvent event);
}
