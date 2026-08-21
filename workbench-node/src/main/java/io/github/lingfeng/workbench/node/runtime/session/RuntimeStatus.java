package io.github.lingfeng.workbench.node.runtime.session;

public enum RuntimeStatus {
    IDLE,
    BUSY,
    RETRY,
    WAITING_INTERACTION,
    ERROR,
    ABORTED
}
