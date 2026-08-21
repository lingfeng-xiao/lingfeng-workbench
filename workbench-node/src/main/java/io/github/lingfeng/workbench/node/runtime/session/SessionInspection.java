package io.github.lingfeng.workbench.node.runtime.session;

public record SessionInspection(boolean sameSession, boolean alive, RuntimeStatus status, String summary) {
}
