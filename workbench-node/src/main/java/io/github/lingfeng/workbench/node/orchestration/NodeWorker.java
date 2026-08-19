package io.github.lingfeng.workbench.node.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.connection.NodeProtocolClient;
import io.github.lingfeng.workbench.node.connection.ProtocolClientException;
import io.github.lingfeng.workbench.node.localstate.LocalNodeStore;
import io.github.lingfeng.workbench.node.protocol.PollResponse;
import io.github.lingfeng.workbench.node.runtime.RuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class NodeWorker {

    private static final Logger logger = LoggerFactory.getLogger(NodeWorker.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final NodeProperties properties;
    private final LocalNodeStore localNodeStore;
    private final NodeProtocolClient protocolClient;
    private final RuntimeAdapter runtimeAdapter;
    private final AssignmentExecutor assignmentExecutor;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public NodeWorker(
            NodeProperties properties,
            LocalNodeStore localNodeStore,
            NodeProtocolClient protocolClient,
            RuntimeAdapter runtimeAdapter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.localNodeStore = localNodeStore;
        this.protocolClient = protocolClient;
        this.runtimeAdapter = runtimeAdapter;
        NodeEventFactory eventFactory = new NodeEventFactory(properties, objectMapper, clock);
        this.assignmentExecutor = new AssignmentExecutor(
                properties, localNodeStore, protocolClient, runtimeAdapter, eventFactory);
    }

    public void runUntilInterrupted() {
        RuntimeProbe probe = runtimeAdapter.probe();
        if (!probe.available()) {
            throw new IllegalStateException(probe.summary());
        }
        int retryCount = 0;
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            try {
                runCycle();
                retryCount = 0;
                sleep(properties.pollInterval());
            } catch (ProtocolClientException exception) {
                if (!exception.isRetryable()) {
                    throw exception;
                }
                retryCount++;
                Duration retryDelay = retryDelay(retryCount);
                logger.warn("Node protocol unavailable retryCount={} retryDelayMs={}",
                        retryCount, retryDelay.toMillis());
                sleep(retryDelay);
            }
        }
    }

    public void stop() {
        stopping.set(true);
        runtimeAdapter.cancel();
    }

    void runCycle() {
        protocolClient.hello(runtimeAdapter.capabilities().capabilities());
        assignmentExecutor.flushOutbox();
        protocolClient.heartbeat(null);
        PollResponse pollResponse = protocolClient.poll(localNodeStore.acknowledgedCommandIds());
        if (pollResponse instanceof PollResponse.AssignmentCommand command) {
            assignmentExecutor.execute(command.assignment());
        }
    }

    private static Duration retryDelay(int retryCount) {
        int boundedExponent = Math.min(retryCount - 1, 5);
        long seconds = Math.min(1L << boundedExponent, MAX_RETRY_DELAY.toSeconds());
        return Duration.ofSeconds(seconds);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
