package io.github.lingfeng.workbench.service.api;

import jakarta.servlet.http.HttpServletRequest;

public final class ApiErrors {
    public static final String REQUEST_ID_ATTRIBUTE = ApiErrors.class.getName() + ".requestId";
    public static final String PROTOCOL_MESSAGE_ID_ATTRIBUTE = ApiErrors.class.getName() + ".protocolMessageId";

    private ApiErrors() {}

    public static Object forRequest(HttpServletRequest request, String code, String message) {
        String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (request.getRequestURI().startsWith("/api/node/")) {
            String messageId = (String) request.getAttribute(PROTOCOL_MESSAGE_ID_ATTRIBUTE);
            return new ProtocolError(code, message, messageId == null ? requestId : messageId);
        }
        return new ApiError(code, message, requestId);
    }

    public record ApiError(String code, String message, String requestId) {}
    public record ProtocolError(String code, String message, String requestMessageId) {}
}
