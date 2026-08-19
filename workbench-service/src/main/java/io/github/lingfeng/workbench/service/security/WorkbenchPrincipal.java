package io.github.lingfeng.workbench.service.security;

public record WorkbenchPrincipal(Kind kind, String nodeId) {
    public enum Kind { HERMES, SITES, NODE }
}
