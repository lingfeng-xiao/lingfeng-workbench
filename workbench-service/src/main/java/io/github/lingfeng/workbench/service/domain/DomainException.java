package io.github.lingfeng.workbench.service.domain;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DomainException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static DomainException notFound(String entity, String id) {
        return new DomainException("not_found", entity + " not found: " + id, HttpStatus.NOT_FOUND);
    }

    public static DomainException conflict(String message) {
        return new DomainException("state_conflict", message, HttpStatus.CONFLICT);
    }

    public static DomainException rejected(String message) {
        return new DomainException("message_rejected", message, HttpStatus.BAD_REQUEST);
    }
}
