package io.github.lingfeng.workbench.node.connection;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.localstate.NodeBusyException;
import io.github.lingfeng.workbench.node.orchestration.RunSupervisor;
import io.github.lingfeng.workbench.node.protocol.v2.DurableNodeEvent;
import io.github.lingfeng.workbench.node.protocol.v2.PollResult;
import io.github.lingfeng.workbench.node.protocol.v2.ProtocolAck;
import io.github.lingfeng.workbench.node.runtime.RuntimeProbe;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServiceConnectionLoop implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ServiceConnectionLoop.class);

    private final NodeProperties properties;
    private final ControlLoopStore store;
    private final ControlLoopProtocolClient service;
    private final SessionRuntimeAdapter runtime;
    private final RunSupervisor supervisor;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private Instant lastHeartbeat = Instant.EPOCH;
    private boolean helloComplete;

    public ServiceConnectionLoop(
            NodeProperties properties,
            ControlLoopStore store,
            ControlLoopProtocolClient service,
            SessionRuntimeAdapter runtime,
            RunSupervisor supervisor) {
        this.properties = properties;
        this.store = store;
        this.service = service;
        this.runtime = runtime;
        this.supervisor = supervisor;
    }

    public void runUntilInterrupted() {
        RuntimeProbe runtimeProbe;
        try {
            runtimeProbe = runtime.probe().toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Runtime preflight failed", exception.getCause());
        }
        if (!runtimeProbe.available()) {
            throw new IllegalStateException("Runtime preflight failed: " + runtimeProbe.summary());
        }
        logger.info("Runtime preflight succeeded runtimeKind={}", runtime.capabilities().runtimeKind());
        supervisor.recover();
        int retryCount = 0;
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            try {
                runCycle();
                retryCount = 0;
                sleep(properties.pollInterval());
            } catch (ProtocolClientException exception) {
                retryCount++;
                Duration retryDelay = retryDelay(retryCount);
                if (exception.isRetryable()) {
                    logger.warn("Service connection unavailable retryCount={} retryDelayMs={} category={}",
                            retryCount, retryDelay.toMillis(), exception.getMessage());
                } else {
                    logger.error("Service protocol failed closed retryDelayMs={} category={}",
                            retryDelay.toMillis(), exception.getMessage());
                }
                sleep(retryDelay);
            }
        }
    }

    void runCycle() {
        if (!helloComplete) {
            service.hello(runtime.capabilities().values());
            helloComplete = true;
            logger.info("Service preflight succeeded protocolVersion=2.0 nodeId={}", properties.nodeId());
        }
        flushOutbox();
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeat, now).compareTo(properties.heartbeatInterval()) >= 0) {
            service.heartbeat(store.activeRunId(), store.activeRunState());
            lastHeartbeat = now;
        }
        PollResult pollResult = service.poll();
        if (pollResult instanceof PollResult.Command commandResult) {
            storeAndDispatch(commandResult);
        }
    }

    public void flushOutbox() {
        for (DurableNodeEvent event : store.pendingEvents(100)) {
            ProtocolAck acknowledgement = service.sendEvent(event);
            if (!event.messageId().equals(acknowledgement.requestMessageId())) {
                throw new ProtocolClientException("Service acknowledged a different event", false);
            }
            store.acknowledgeEvent(event.messageId());
        }
    }

    private void storeAndDispatch(PollResult.Command commandResult) {
        try {
            ControlLoopStore.StoredCommand stored = store.storeCommand(commandResult.command());
            if (stored.status() == ControlLoopStore.StoreStatus.NEW) {
                supervisor.acceptStoredCommand(commandResult.command());
            }
        } catch (NodeBusyException exception) {
            logger.warn("Node is busy; START_RUN remains unacknowledged for Service redelivery");
        }
    }

    private Duration retryDelay(int retryCount) {
        int exponent = Math.min(retryCount - 1, 30);
        long multiplier = 1L << exponent;
        long initialMillis = properties.backoffInitial().toMillis();
        long maximumMillis = properties.backoffMaximum().toMillis();
        long calculated = initialMillis > maximumMillis / multiplier
                ? maximumMillis
                : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(calculated, maximumMillis));
    }

    public void stop() {
        stopping.set(true);
    }

    @Override
    public void close() {
        stop();
        if (runtime instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                logger.warn("Runtime adapter close failed", exception);
            }
        }
        supervisor.close();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
