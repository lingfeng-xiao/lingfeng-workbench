package io.github.lingfeng.workbench.service.api.node;

import io.github.lingfeng.workbench.service.api.node.NodeDtos.Acknowledgement;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.HeartbeatRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.HelloRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.PollRequest;
import io.github.lingfeng.workbench.service.api.node.NodeDtos.RunEvent;
import io.github.lingfeng.workbench.service.application.NodeApplicationService;
import io.github.lingfeng.workbench.service.domain.DomainException;
import io.github.lingfeng.workbench.service.security.WorkbenchPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/node/v1")
class NodeController {
    private final NodeApplicationService service;

    NodeController(NodeApplicationService service) {
        this.service = service;
    }

    @PostMapping("/hello")
    Acknowledgement hello(Authentication authentication, @Valid @RequestBody HelloRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.registerNode(request);
    }

    @PostMapping("/heartbeat")
    Acknowledgement heartbeat(Authentication authentication, @Valid @RequestBody HeartbeatRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.heartbeat(request);
    }

    @PostMapping("/poll")
    Object poll(Authentication authentication, @Valid @RequestBody PollRequest request) {
        requireBoundNode(authentication, request.nodeId());
        return service.poll(request);
    }

    @PostMapping("/events")
    Acknowledgement events(Authentication authentication, @Valid @RequestBody RunEvent event) {
        requireBoundNode(authentication, event.nodeId());
        return service.recordEvent(event);
    }

    private void requireBoundNode(Authentication authentication, String claimedNodeId) {
        WorkbenchPrincipal principal = (WorkbenchPrincipal) authentication.getPrincipal();
        if (!principal.nodeId().equals(claimedNodeId)) {
            throw new DomainException("forbidden", "Credential is not bound to the claimed node",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
    }
}
