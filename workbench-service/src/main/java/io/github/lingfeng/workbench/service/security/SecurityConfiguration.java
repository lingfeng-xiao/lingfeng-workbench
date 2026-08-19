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
            HttpSecurity http, BearerAuthenticationFilter bearerFilter, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/client/v1/work-items").hasAuthority("CLIENT_CREATE")
                        .requestMatchers("/api/client/v1/**").hasAuthority("CLIENT_READ")
                        .requestMatchers("/api/node/v1/**").hasAuthority("NODE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    objectMapper.writeValue(response.getOutputStream(),
                            ApiErrors.forRequest(request, "forbidden", "Credential lacks the required scope"));
                }))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
