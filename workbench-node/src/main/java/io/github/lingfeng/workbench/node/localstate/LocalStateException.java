package io.github.lingfeng.workbench.node.localstate;

public class LocalStateException extends RuntimeException {

    public LocalStateException(String message, Throwable cause) {
        super(message, cause);
    }

    public LocalStateException(String message) {
        super(message);
    }
}
