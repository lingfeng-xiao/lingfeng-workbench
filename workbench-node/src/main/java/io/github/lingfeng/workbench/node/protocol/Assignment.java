package io.github.lingfeng.workbench.node.protocol;

public record Assignment(
        String commandId,
        String workItemId,
        String missionId,
        String runId,
        int missionRevision,
        String missionDigest,
        String objective,
        String acceptanceSummary,
        String authorizedSideEffectsSummary,
        String targetNodeId,
        String workspaceRef,
        String runtimeKind,
        String executionProfile) {
}
