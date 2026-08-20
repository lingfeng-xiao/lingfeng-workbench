package io.github.lingfeng.workbench.node;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import io.github.lingfeng.workbench.node.connection.ServiceConnectionLoop;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(NodeProperties.class)
public class WorkbenchNodeApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WorkbenchNodeApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @Bean
    ApplicationRunner nodeWorkerRunner(
            ServiceConnectionLoop connectionLoop) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments arguments) {
                connectionLoop.runUntilInterrupted();
            }
        };
    }
}
