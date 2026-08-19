package io.github.lingfeng.workbench.service;

import io.github.lingfeng.workbench.service.config.WorkbenchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(WorkbenchProperties.class)
public class WorkbenchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkbenchServiceApplication.class, args);
    }
}
