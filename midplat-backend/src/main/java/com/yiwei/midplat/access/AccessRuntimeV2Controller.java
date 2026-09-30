package com.yiwei.midplat.access;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * W1 machine-facing runtime endpoints. Authenticated by the caller's Bearer key
 * (never the management session); kept outside the /api/access management domain.
 */
@RestController
@RequestMapping("/api/runtime/v2")
public class AccessRuntimeV2Controller {

    private final AccessAuthentication authentication;

    AccessRuntimeV2Controller(AccessAuthentication authentication) {
        this.authentication = authentication;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var who = authentication.require(authorization);
        return Map.of(
                "clientId", who.clientId(),
                "consumerId", who.consumerId(),
                "projectId", who.projectId(),
                "environment", who.environment(),
                "keyId", who.keyId(),
                "clientPolicyRevision", who.clientPolicyRevision(),
                "credentialPolicyRevision", who.credentialPolicyRevision());
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var who = authentication.require(authorization);
        return Map.of("allowedActions", authentication.allowedActions(who));
    }
}
