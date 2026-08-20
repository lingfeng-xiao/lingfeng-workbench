package io.github.lingfeng.workbench.service.api.node.v2;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.*;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class NodeV2Dtos {
    private NodeV2Dtos() {}

    public record HelloRequest(@NotBlank @Pattern(regexp="2\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp=IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String nodeId, @NotNull Instant sentAt,
            @NotBlank @Size(max=800) String displayName,
            @NotNull @Size(max=32) List<@NotBlank @Pattern(regexp=IDENTIFIER) String> capabilities) {}
    public record HeartbeatRequest(@NotBlank @Pattern(regexp="2\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp=IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String nodeId, @NotNull Instant sentAt,
            @Pattern(regexp=RUN_ID) String activeRunId,
            @Pattern(regexp="received|preparing|opening_session|running|waiting_interaction|resuming|cancelling") String activeRunState) {}
    public record PollRequest(@NotBlank @Pattern(regexp="2\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp=IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String nodeId, @NotNull Instant sentAt,
            @Min(1) @Max(1) Integer maxCommands) {}
    public record NodeEvent(@NotBlank @Pattern(regexp="2\\.0") String protocolVersion,
            @NotBlank @Pattern(regexp=IDENTIFIER) String messageId,
            @NotBlank @Pattern(regexp=IDENTIFIER) String nodeId, @NotNull Instant sentAt,
            @NotBlank @Pattern(regexp="COMMAND_STORED|RUN_STARTED|PHASE_CHANGED|PROGRESS_UPDATED|INTERACTION_REQUESTED|INTERACTION_RESPONSE_CONSUMED|RUN_TERMINAL") String eventType,
            @Min(1) long localSequence,
            @NotBlank @Pattern(regexp=WORK_ITEM_ID) String workItemId,
            @NotBlank @Pattern(regexp=MISSION_ID) String missionId,
            @NotBlank @Pattern(regexp=RUN_ID) String runId,
            @NotBlank @Pattern(regexp=DIGEST) String missionDigest,
            @Pattern(regexp=IDENTIFIER) String commandId,
            @Pattern(regexp=DIGEST) String commandPayloadDigest,
            Boolean resumable,
            @Pattern(regexp="CONTRACT_REVIEW|CONTEXT_FREEZE|IMPLEMENTATION|BUILD_VALIDATION|API_VALIDATION|REPORTING") String phaseCode,
            @Size(min=1,max=800) String phaseSummary,
            @Size(min=1,max=800) String progressSummary,
            @Pattern(regexp=INTERACTION_ID) String interactionId,
            @Pattern(regexp=IDENTIFIER) String checkpointId,
            @Pattern(regexp=IDENTIFIER) String targetNodeId,
            @Size(min=1,max=800) String promptSummary,
            @Size(min=1,max=3) List<@Pattern(regexp="APPROVE|REJECT|PROVIDE_INPUT") String> allowedDecisions,
            @Pattern(regexp=IDENTIFIER) String responseCommandId,
            @Pattern(regexp="SUCCEEDED|FAILED|INTERRUPTED|UNKNOWN") String runtimeOutcome,
            @Pattern(regexp="PASSED|FAILED|UNKNOWN") String acceptanceStatus,
            @Size(min=1,max=800) String resultSummary) {}
    public record Acknowledgement(String requestMessageId, boolean duplicate) {}
    public record NoCommand(boolean commandAvailable) { public NoCommand() { this(false); } }
}
