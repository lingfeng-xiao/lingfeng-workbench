package io.github.lingfeng.workbench.node.runtime.opencode;

public record OpenCodePromptTarget(String agent, String providerId, String modelId) {

    public OpenCodePromptTarget {
        agent = requireText(agent, "agent");
        providerId = requireText(providerId, "providerId");
        modelId = requireText(modelId, "modelId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OpenCode " + fieldName + " is required");
        }
        return value;
    }
}
