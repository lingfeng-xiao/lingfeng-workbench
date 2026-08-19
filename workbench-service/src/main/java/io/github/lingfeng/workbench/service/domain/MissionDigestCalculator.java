package io.github.lingfeng.workbench.service.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class MissionDigestCalculator {

    public String calculate(MissionContract mission) {
        String canonical = encode(mission.objective())
                + encode(mission.acceptanceSummary())
                + encode(mission.authorizedSideEffectsSummary())
                + encode(mission.targetNodeId())
                + encode(mission.workspaceRef())
                + encode(mission.runtimeKind())
                + encode(mission.executionProfile())
                + mission.revision();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encode(String value) {
        return value.length() + ":" + value;
    }

    public record MissionContract(
            String objective,
            String acceptanceSummary,
            String authorizedSideEffectsSummary,
            String targetNodeId,
            String workspaceRef,
            String runtimeKind,
            String executionProfile,
            int revision) {}
}
