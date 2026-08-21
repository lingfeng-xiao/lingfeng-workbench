package io.github.lingfeng.workbench.node.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lingfeng.workbench.node.connection.ControlLoopProtocolClient;
import io.github.lingfeng.workbench.node.connection.HttpsControlLoopProtocolClient;
import io.github.lingfeng.workbench.node.connection.NodeHttpClientFactory;
import io.github.lingfeng.workbench.node.connection.ServiceConnectionLoop;
import io.github.lingfeng.workbench.node.context.ContextRegistry;
import io.github.lingfeng.workbench.node.context.ContextRegistryProperties;
import io.github.lingfeng.workbench.node.localstate.ControlLoopStore;
import io.github.lingfeng.workbench.node.orchestration.AcceptanceProfileRegistry;
import io.github.lingfeng.workbench.node.orchestration.RunSupervisor;
import io.github.lingfeng.workbench.node.orchestration.DeterministicFakeAcceptanceEvaluator;
import io.github.lingfeng.workbench.node.orchestration.LocalCommandAcceptanceEvaluator;
import io.github.lingfeng.workbench.node.runtime.fake.FakeSessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.opencode.HttpOpenCodeClient;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodeClient;
import io.github.lingfeng.workbench.node.runtime.opencode.OpenCodePromptTarget;
import io.github.lingfeng.workbench.node.runtime.session.SessionRuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.ws.WsEndpointResolver;
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
                    properties.fakeScenario(), properties.fakeEventDelay());
            case "ws" -> {
                OpenCodeClient client = new HttpOpenCodeClient(
                        properties.wsBaseUri(), properties.connectTimeout(),
                        properties.requestTimeout(), objectMapper);
                OpenCodePromptTarget promptTarget = new OpenCodePromptTarget(
                        properties.wsAgent(), properties.wsProviderId(), properties.wsModelId());
                WsEndpointResolver resolver = new WsEndpointResolver(
                        properties.wsBaseUri(), properties.wsExpectedVersion(), client, promptTarget);
                yield new WsSessionRuntimeAdapter(
                        client, resolver, promptTarget, objectMapper,
                        properties.wsReconcileInterval(), properties.wsReconnectDelay());
            }
            default -> throw new IllegalArgumentException("Unsupported runtimeKind: " + properties.runtimeKind());
        };
    }

    @Bean(destroyMethod = "close")
    RunSupervisor runSupervisor(
            NodeProperties properties,
            ContextRegistryProperties contextRegistryProperties,
            AcceptanceProperties acceptanceProperties,
            ControlLoopStore store,
            SessionRuntimeAdapter runtime,
            ObjectMapper objectMapper,
            Clock clock) {
        return new RunSupervisor(properties, store, runtime,
                properties.runtimeKind().equals("fake-session")
                        ? new DeterministicFakeAcceptanceEvaluator()
                        : configuredAcceptanceEvaluator(acceptanceProperties, objectMapper, clock),
                new ContextRegistry(properties, contextRegistryProperties));
    }

    private static LocalCommandAcceptanceEvaluator configuredAcceptanceEvaluator(
            AcceptanceProperties properties, ObjectMapper objectMapper, Clock clock) {
        return new LocalCommandAcceptanceEvaluator(
                new AcceptanceProfileRegistry(properties), objectMapper, clock);
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
