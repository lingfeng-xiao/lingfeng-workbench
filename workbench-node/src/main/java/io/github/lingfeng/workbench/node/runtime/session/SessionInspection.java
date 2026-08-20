package io.github.lingfeng.workbench.node.runtime.session;

public record SessionInspection(boolean sameSession, boolean alive, boolean resumable, String summary) {
}
