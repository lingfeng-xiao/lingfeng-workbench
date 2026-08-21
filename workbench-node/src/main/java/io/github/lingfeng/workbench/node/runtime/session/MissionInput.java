package io.github.lingfeng.workbench.node.runtime.session;

public record MissionInput(
        String objective,
        String acceptanceSummary,
        String authorizedSideEffectsSummary,
        String executionProfile) {

    public MissionInput {
        objective = requireText(objective, "objective");
        acceptanceSummary = requireText(acceptanceSummary, "acceptanceSummary");
        authorizedSideEffectsSummary = requireText(authorizedSideEffectsSummary, "authorizedSideEffectsSummary");
        executionProfile = requireText(executionProfile, "executionProfile");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
