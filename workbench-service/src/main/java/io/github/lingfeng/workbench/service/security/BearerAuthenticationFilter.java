package io.github.lingfeng.workbench.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingfeng.workbench.service.api.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final CredentialAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    BearerAuthenticationFilter(CredentialAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(request, response);
            return;
        }
        WorkbenchPrincipal principal = authenticator.authenticate(authorization.substring(7)).orElse(null);
        if (principal == null) {
            writeUnauthorized(request, response);
            return;
        }
        var authorities = switch (principal.kind()) {
            case HERMES -> List.of(new SimpleGrantedAuthority("CLIENT_READ"), new SimpleGrantedAuthority("CLIENT_CREATE"));
            case SITES -> List.of(new SimpleGrantedAuthority("CLIENT_READ"));
            case NODE -> List.of(new SimpleGrantedAuthority("NODE"));
        };
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiErrors.forRequest(
                request, "unauthorized", "Missing or invalid bearer credential"));
    }
}
