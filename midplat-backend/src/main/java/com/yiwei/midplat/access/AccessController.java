package com.yiwei.midplat.access;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.fusion.FusionAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * W1 management endpoints for consumers/clients/credentials, under the management
 * authentication domain /api/access (see {@link FusionAccess#shouldNotFilter}); machine
 * keys are rejected by that filter before reaching here.
 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

    private final AccessAdminService admin;
    private final AccessAuthentication authentication;

    AccessController(AccessAdminService admin, AccessAuthentication authentication) {
        this.admin = admin;
        this.authentication = authentication;
    }

    // ---- consumers ----

    public record ConsumerCmd(String code, String name, String type) {}

    @GetMapping("/consumers")
    ApiResponse<List<ConsumerView>> consumers(HttpServletRequest request) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.listConsumers().stream().map(AccessController::view).toList());
    }

    @PostMapping("/consumers")
    ApiResponse<ConsumerView> createConsumer(HttpServletRequest request, @RequestBody ConsumerCmd cmd) {
        FusionAccess.admin(request);
        return ApiResponse.ok(view(admin.createConsumer(cmd.code(), cmd.name(), cmd.type())));
    }

    // ---- clients ----

    public record ClientCmd(String consumerId, String projectId, String environment, String name, String code) {}
    public record ClientStatusCmd(String status) {}

    @GetMapping("/clients")
    ApiResponse<List<ClientView>> clients(HttpServletRequest request, @RequestParam(required = false) String projectId) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.listClients(projectId).stream().map(AccessController::view).toList());
    }

    @PostMapping("/clients")
    ApiResponse<ClientView> createClient(HttpServletRequest request, @RequestBody ClientCmd cmd) {
        FusionAccess.admin(request);
        return ApiResponse.ok(view(admin.createClient(cmd.consumerId(), cmd.projectId(), cmd.environment(), cmd.name(), cmd.code())));
    }

    @PutMapping("/clients/{id}/status")
    ApiResponse<ClientView> clientStatus(HttpServletRequest request, @PathVariable String id, @RequestBody ClientStatusCmd cmd) {
        FusionAccess.admin(request);
        return ApiResponse.ok(view(admin.changeClientStatus(id, cmd.status())));
    }

    @GetMapping("/clients/{id}/entitlements")
    ApiResponse<List<EntitlementView>> entitlements(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.listEntitlements(id).stream().map(AccessController::view).toList());
    }

    @PutMapping("/clients/{id}/entitlements")
    ApiResponse<List<EntitlementView>> setEntitlements(HttpServletRequest request, @PathVariable String id,
                                                       @RequestBody EntitlementsCmd cmd) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.setEntitlements(id, cmd.commands()).stream().map(AccessController::view).toList());
    }

    // ---- credentials ----

    public record IssueCredentialCmd(String label, Instant expiresAt, String idempotencyKey) {}
    public record RotateCmd(String label) {}
    public record GrantsCmd(List<AccessAdminService.GrantCmd> grants) {}
    public record EntitlementsCmd(List<AccessAdminService.EntitlementCmd> commands) {}

    @GetMapping("/clients/{id}/credentials")
    ApiResponse<List<AccessAdminService.CredentialView>> credentials(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.listCredentials(id));
    }

    /** The one-time secret appears only in this response, only on first issue. */
    @PostMapping("/clients/{id}/credentials")
    ApiResponse<Map<String, Object>> issueCredential(HttpServletRequest request, @PathVariable String id, @RequestBody IssueCredentialCmd cmd) {
        FusionAccess.admin(request);
        var issued = admin.issueCredential(id, cmd.label(), cmd.expiresAt(), cmd.idempotencyKey());
        if (!issued.secretAvailable()) {
            return ApiResponse.ok(Map.of("credential", issued.view(), "secretAvailable", false));
        }
        return ApiResponse.ok(Map.of("credential", issued.view(), "secretAvailable", true, "secret", issued.secret()));
    }

    @PostMapping("/credentials/{id}/rotations")
    ApiResponse<Map<String, Object>> rotate(HttpServletRequest request, @PathVariable String id, @RequestBody RotateCmd cmd) {
        FusionAccess.admin(request);
        var issued = admin.rotateCredential(id, cmd.label());
        return ApiResponse.ok(Map.of("credential", issued.view(), "secretAvailable", true, "secret", issued.secret()));
    }

    @PostMapping("/credentials/{id}/revoke")
    ApiResponse<AccessAdminService.CredentialView> revoke(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.revokeCredential(id));
    }

    @PutMapping("/credentials/{id}/grants")
    ApiResponse<List<GrantView>> setGrants(HttpServletRequest request, @PathVariable String id, @RequestBody GrantsCmd cmd) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.setGrants(id, cmd.grants()).stream().map(AccessController::view).toList());
    }

    @GetMapping("/credentials/{id}/grants")
    ApiResponse<List<GrantView>> grants(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(admin.listGrants(id).stream().map(AccessController::view).toList());
    }

    // ---- views ----

    public record ConsumerView(String id, String code, String name, String type, String status) {}
    public record ClientView(String id, String consumerId, String projectId, String environment, String name, String code, String status, long policyRevision) {}
    public record EntitlementView(String id, String resourceKind, String resourceId, String action, String constraints, String status) {}
    public record GrantView(String id, String entitlementId, String constraints, String status) {}

    static ConsumerView view(AccessConsumer c) {
        return new ConsumerView(c.getId(), c.getCode(), c.getName(), c.getType(), c.getStatus());
    }

    static ClientView view(AccessClient c) {
        return new ClientView(c.getId(), c.getConsumerId(), c.getProjectId(), c.getEnvironment(), c.getName(), c.getCode(), c.getStatus(), c.getPolicyRevision());
    }

    static EntitlementView view(AccessEntitlement e) {
        return new EntitlementView(e.getId(), e.getResourceKind(), e.getResourceId(), e.getAction(), e.getConstraints(), e.getStatus());
    }

    static GrantView view(AccessGrant g) {
        return new GrantView(g.getId(), g.getEntitlementId(), g.getConstraints(), g.getStatus());
    }
}
