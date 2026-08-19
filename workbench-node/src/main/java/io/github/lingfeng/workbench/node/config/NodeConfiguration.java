package io.github.lingfeng.workbench.node.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lingfeng.workbench.node.connection.HttpsNodeProtocolClient;
import io.github.lingfeng.workbench.node.connection.NodeProtocolClient;
import io.github.lingfeng.workbench.node.localstate.LocalNodeStore;
import io.github.lingfeng.workbench.node.runtime.RuntimeAdapter;
import io.github.lingfeng.workbench.node.runtime.ws.WsRuntimeAdapter;
import java.net.http.HttpClient;
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
    LocalNodeStore localNodeStore(NodeProperties properties, ObjectMapper objectMapper) {
        return new LocalNodeStore(properties.stateDirectory(), objectMapper);
    }

    @Bean
    NodeProtocolClient nodeProtocolClient(NodeProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
        return new HttpsNodeProtocolClient(properties, objectMapper, httpClient);
    }

    @Bean
    RuntimeAdapter runtimeAdapter(NodeProperties properties, ObjectMapper objectMapper) {
        if (!"ws".equals(properties.runtimeKind())) {
            throw new IllegalArgumentException("Unsupported runtimeKind: " + properties.runtimeKind());
        }
        return new WsRuntimeAdapter(properties.wsExecutable(), objectMapper);
    }
}
