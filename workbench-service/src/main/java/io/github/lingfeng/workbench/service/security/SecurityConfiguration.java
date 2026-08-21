package io.github.lingfeng.workbench.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.ApiErrors;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, BearerAuthenticationFilter bearerFilter, ObjectMapper objectMapper)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/tasks/v1/**")
                    .hasAuthority("TASK_READ")
                    .requestMatchers(HttpMethod.POST, "/api/tasks/v1/**")
                    .hasAuthority("TASK_WRITE")
                    .requestMatchers(HttpMethod.PUT, "/api/tasks/v1/**")
                    .hasAuthority("TASK_WRITE")
                    .requestMatchers(HttpMethod.POST, "/api/client/v2/work-items")
                    .hasAuthority("V2_CREATE")
                    .requestMatchers(HttpMethod.POST, "/api/client/v2/interactions/*/resolution")
                    .hasAuthority("INTERACTION_RESOLVE")
                    .requestMatchers(HttpMethod.POST, "/api/client/v2/notifications/poll")
                    .hasAuthority("NOTIFICATION_PULL")
                    .requestMatchers(
                        HttpMethod.POST, "/api/client/v2/notifications/*/delivery-events")
                    .hasAuthority("NOTIFICATION_REPORT")
                    .requestMatchers(HttpMethod.GET, "/api/client/v2/**")
                    .hasAuthority("CLIENT_READ")
                    .requestMatchers("/api/node/v2/**")
                    .hasAuthority("NODE")
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors.accessDeniedHandler(
                    (request, response, exception) -> {
                      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                      response.setContentType("application/json");
                      objectMapper.writeValue(
                          response.getOutputStream(),
                          ApiErrors.forRequest(
                              request, "forbidden", "Credential lacks the required scope"));
                    }))
        .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
