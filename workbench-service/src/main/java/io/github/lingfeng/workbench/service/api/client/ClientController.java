package io.github.lingfeng.workbench.service.api.client;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.IDENTIFIER;
import static io.github.lingfeng.workbench.service.api.ValidationPatterns.MISSION_ID;
import static io.github.lingfeng.workbench.service.api.ValidationPatterns.RUN_ID;
import static io.github.lingfeng.workbench.service.api.ValidationPatterns.WORK_ITEM_ID;

import io.github.lingfeng.workbench.service.api.client.ClientDtos.CreateWorkItemRequest;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.CreatedWorkItem;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.InteractionSummary;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.MissionDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.NodeSummary;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.RunDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.WorkItemDetail;
import io.github.lingfeng.workbench.service.api.client.ClientDtos.WorkItemSummary;
import io.github.lingfeng.workbench.service.application.ClientApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/client/v1")
class ClientController {
    private final ClientApplicationService service;

    ClientController(ClientApplicationService service) {
        this.service = service;
    }

    @PostMapping("/work-items")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedWorkItem createWorkItem(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDENTIFIER) String idempotencyKey,
            @Valid @RequestBody CreateWorkItemRequest request) {
        return service.createWorkItem(idempotencyKey, request);
    }

    @GetMapping("/work-items")
    List<WorkItemSummary> listWorkItems(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.listWorkItems(limit);
    }

    @GetMapping("/work-items/{workItemId}")
    WorkItemDetail getWorkItem(@PathVariable @Pattern(regexp = WORK_ITEM_ID) String workItemId) {
        return service.getWorkItem(workItemId);
    }

    @GetMapping("/missions/{missionId}")
    MissionDetail getMission(@PathVariable @Pattern(regexp = MISSION_ID) String missionId) {
        return service.getMission(missionId);
    }

    @GetMapping("/runs/{runId}")
    RunDetail getRun(@PathVariable @Pattern(regexp = RUN_ID) String runId) {
        return service.getRun(runId);
    }

    @GetMapping("/nodes")
    List<NodeSummary> listNodes() {
        return service.listNodes();
    }

    @GetMapping("/interactions")
    List<InteractionSummary> listInteractions(
            @RequestParam(required = false)
            @Pattern(regexp = "pending|resolved|delivered|consumed|expired|cancelled") String state) {
        return service.listInteractions(state);
    }
}
