package io.github.lingfeng.workbench.node.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lingfeng.workbench.node.connection.ControlLoopProtocolClient;
import io.github.lingfeng.workbench.node.connection.HttpsControlLoopProtocolClient;
import io.github.lingfeng.workbench.node.connection.NodeHttpClientFactory;
import io.github.lingfeng.workbench.node.connection.ServiceConnectionLoop;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.orchestration.RunSupervisor;
import io.github.lingfeng.workbench.node.runtime.fake.FakeSessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.ws.WsRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.ws.WsSessionRuntimeAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NodeConfiguration {

    @Bean
    ObjectMapper protocolObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Bean
    Clock nodeClock() {
        return Clock.systemUTC();
    }

    @Bean
    ControlLoopStore controlLoopStore(
            NodeProperties properties, ObjectMapper objectMapper, Clock clock) {
        return new ControlLoopStore(properties.stateDirectory(), properties.nodeId(), objectMapper, clock);
    }

    @Bean
    ControlLoopProtocolClient controlLoopProtocolClient(
            NodeProperties properties, ObjectMapper objectMapper, Clock clock) {
        return new HttpsControlLoopProtocolClient(
                properties, objectMapper, NodeHttpClientFactory.create(properties), clock);
    }

    @Bean(destroyMethod = "close")
    SessionRuntimeAdapter sessionRuntimeAdapter(NodeProperties properties, ObjectMapper objectMapper) {
        return switch (properties.runtimeKind()) {
            case "fake-session" -> new FakeSessionRuntimeAdapter(
                    properties.fakeScenario(), properties.fakeTurnDelay(), objectMapper);
            case "ws" -> new WsSessionRuntimeAdapter(
                    new WsRuntimeAdapter(properties.wsExecutable(), objectMapper));
            default -> throw new IllegalArgumentException("Unsupported runtimeKind: " + properties.runtimeKind());
        };
    }

    @Bean(destroyMethod = "close")
    RunSupervisor runSupervisor(
            NodeProperties properties, ControlLoopStore store, SessionRuntimeAdapter runtime) {
        return new RunSupervisor(properties, store, runtime);
    }

    @Bean(destroyMethod = "close")
    ServiceConnectionLoop serviceConnectionLoop(
            NodeProperties properties,
            ControlLoopStore store,
            ControlLoopProtocolClient protocolClient,
            SessionRuntimeAdapter runtime,
            RunSupervisor supervisor) {
        return new ServiceConnectionLoop(properties, store, protocolClient, runtime, supervisor);
    }
}
