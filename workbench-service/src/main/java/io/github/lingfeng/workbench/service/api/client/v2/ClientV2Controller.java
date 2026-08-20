package io.github.lingfeng.workbench.service.api.client.v2;

import static io.github.lingfeng.workbench.service.api.ValidationPatterns.*;
import io.github.lingfeng.workbench.service.api.client.v2.ClientV2Dtos.*;
import io.github.lingfeng.workbench.service.application.ClientV2ApplicationService;
import io.github.lingfeng.workbench.service.security.WorkbenchPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/client/v2")
public class ClientV2Controller {
    private final ClientV2ApplicationService service;

    public ClientV2Controller(ClientV2ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/work-items")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedWorkItem create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") @Pattern(regexp=IDENTIFIER) String idempotencyKey,
            @Valid @RequestBody CreateWorkItemRequest request) {
        return service.create(principalKind(authentication), idempotencyKey, request);
    }

    @GetMapping("/work-items")
    public List<WorkItemSummary> list(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.list(limit);
    }

    @GetMapping("/work-items/{workItemId}")
    public WorkItemDetail detail(@PathVariable @Pattern(regexp=WORK_ITEM_ID) String workItemId) {
        return service.detail(workItemId);
    }

    @GetMapping("/interactions")
    public List<InteractionSummary> interactions(
            @RequestParam(required=false)
            @Pattern(regexp="pending|resolved|delivered|consumed|expired|cancelled") String state,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.interactions(state, limit);
    }

    @GetMapping("/nodes")
    public List<NodeSummary> nodes() {
        return service.nodes();
    }

    @PostMapping("/interactions/{interactionId}/resolution")
    public InteractionResolution resolve(
            Authentication authentication,
            @PathVariable @Pattern(regexp=INTERACTION_ID) String interactionId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp=IDENTIFIER) String idempotencyKey,
            @Valid @RequestBody ResolveInteractionRequest request) {
        return service.resolve(principalKind(authentication), idempotencyKey, interactionId, request);
    }

    @PostMapping("/notifications/poll")
    public Object pollNotification(@Valid @RequestBody NotificationPollRequest request) {
        return service.pollNotification(request);
    }

    @PostMapping("/notifications/{notificationId}/delivery-events")
    public NotificationDeliveryAck report(
            Authentication authentication,
            @PathVariable @Pattern(regexp=NOTIFICATION_ID) String notificationId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp=IDENTIFIER) String idempotencyKey,
            @Valid @RequestBody NotificationDeliveryEvent request) {
        return service.report(principalKind(authentication), idempotencyKey, notificationId, request);
    }

    private String principalKind(Authentication authentication) {
        return ((WorkbenchPrincipal) authentication.getPrincipal()).kind().name();
    }
}
