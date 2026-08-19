package io.github.lingfeng.workbench.node.connection;

public class ProtocolClientException extends RuntimeException {

    private final boolean retryable;

    public ProtocolClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ProtocolClientException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
