package io.github.lingfeng.workbench.service.api;

import io.github.lingfeng.workbench.service.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> domainError(DomainException exception, HttpServletRequest request) {
        log.warn("Request rejected requestId={} code={}", requestId(request), exception.code());
        return ResponseEntity.status(exception.status())
                .body(ApiErrors.forRequest(request, exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Object> invalidRequest(Exception exception, HttpServletRequest request) {
        log.warn("Request validation failed requestId={} type={}", requestId(request), exception.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .body(ApiErrors.forRequest(request, "invalid_request", "Request does not match the published contract"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedError(Exception exception, HttpServletRequest request) {
        log.error("Unexpected request failure requestId={}", requestId(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrors.forRequest(request, "internal_error", "The request could not be completed"));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(ApiErrors.REQUEST_ID_ATTRIBUTE);
    }
}
