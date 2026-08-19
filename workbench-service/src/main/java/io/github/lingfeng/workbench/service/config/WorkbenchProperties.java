package io.github.lingfeng.workbench.service.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workbench")
public record WorkbenchProperties(Security security, Node node) {

    public WorkbenchProperties {
        security = security == null ? new Security("", "", Map.of()) : security;
        node = node == null ? new Node(Duration.ofSeconds(90)) : node;
    }

    public record Security(String hermesToken, String sitesToken, Map<String, String> nodeTokens) {
        public Security {
            hermesToken = hermesToken == null ? "" : hermesToken;
            sitesToken = sitesToken == null ? "" : sitesToken;
            nodeTokens = nodeTokens == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodeTokens));
        }
    }

    public record Node(Duration offlineAfter) {
        public Node {
            offlineAfter = offlineAfter == null ? Duration.ofSeconds(90) : offlineAfter;
        }
    }
}
