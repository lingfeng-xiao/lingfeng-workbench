package io.github.lingfeng.workbench.service.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MissionDigestCalculator {

    public String calculate(MissionContract mission) {
        return sha256(calculateCanonical(mission));
    }

    public String calculate(MissionContract mission, List<String> contextRefs) {
        StringBuilder canonical = new StringBuilder(calculateCanonical(mission));
        contextRefs.forEach(contextRef -> canonical.append(encode(contextRef)));
        return sha256(canonical.toString());
    }

    private String calculateCanonical(MissionContract mission) {
        return encode(mission.objective())
                + encode(mission.acceptanceSummary())
                + encode(mission.authorizedSideEffectsSummary())
                + encode(mission.targetNodeId())
                + encode(mission.workspaceRef())
                + encode(mission.runtimeKind())
                + encode(mission.executionProfile())
                + mission.revision();
    }

    private String sha256(String canonical) {
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
