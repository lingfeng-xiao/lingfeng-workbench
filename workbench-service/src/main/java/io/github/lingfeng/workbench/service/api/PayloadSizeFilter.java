package io.github.lingfeng.workbench.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class PayloadSizeFilter extends OncePerRequestFilter {
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final ObjectMapper objectMapper;

    PayloadSizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST") || request.getMethod().equals("PUT")
                || request.getMethod().equals("PATCH"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (body.length > MAX_PAYLOAD_BYTES) {
            reject(request, response);
            return;
        }
        captureProtocolMessageId(request, body);
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void captureProtocolMessageId(HttpServletRequest request, byte[] body) {
        if (!request.getRequestURI().startsWith("/api/node/")) {
            return;
        }
        try {
            String messageId = objectMapper.readTree(body).path("messageId").asText(null);
            if (messageId != null && messageId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
                request.setAttribute(ApiErrors.PROTOCOL_MESSAGE_ID_ATTRIBUTE, messageId);
            }
        } catch (IOException ignored) {
            // Malformed JSON is reported by the controller boundary with the generated request identifier.
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrors.forRequest(request, "payload_too_large", "Protocol messages are limited to 64 KiB"));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return source.read(); }
            };
        }
    }
}
