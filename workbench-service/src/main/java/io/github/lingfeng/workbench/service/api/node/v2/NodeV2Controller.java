package io.github.lingfeng.workbench.service.api.node.v2;

import io.github.lingfeng.workbench.service.api.node.v2.NodeV2Dtos.*;
import io.github.lingfeng.workbench.service.application.NodeV2ApplicationService;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.security.WorkbenchPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/node/v2")
public class NodeV2Controller {
    private final NodeV2ApplicationService service;

    public NodeV2Controller(NodeV2ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/hello")
    public Acknowledgement hello(Authentication authentication, @Valid @RequestBody HelloRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.hello(request);
    }

    @PostMapping("/heartbeat")
    public Acknowledgement heartbeat(Authentication authentication, @Valid @RequestBody HeartbeatRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.heartbeat(request);
    }

    @PostMapping("/poll")
    public Object poll(Authentication authentication, @Valid @RequestBody PollRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.poll(request);
    }

    @PostMapping("/events")
    public Acknowledgement event(Authentication authentication, @Valid @RequestBody NodeEvent event) {
        requireBoundNode(authentication, event.nodeId());
        return service.event(event);
    }

    private void requireBoundNode(Authentication authentication, String claimedNodeId) {
        WorkbenchPrincipal principal = (WorkbenchPrincipal) authentication.getPrincipal();
        if (!claimedNodeId.equals(principal.nodeId())) {
            throw new DomainException("forbidden", "Credential is not bound to the claimed Node", HttpStatus.FORBIDDEN);
        }
    }
}
