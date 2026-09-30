package com.yiwei.midplat.access;

import com.yiwei.midplat.common.api.ForbiddenException;
import com.yiwei.midplat.common.api.UnauthorizedException;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The single authentication entry for machine callers. Resolves a Bearer key into an
 * {@link AuthenticatedClient}; never falls back to plaintext token lookup on failure.
 */
@Service
public class AccessAuthentication {

    private final AccessCredentialRepository credentials;
    private final AccessClientRepository clients;
    private final AccessConsumerRepository consumers;
    private final AccessEntitlementRepository entitlements;
    private final AccessGrantRepository grants;
    private final AccessSecrets secrets;

    AccessAuthentication(AccessCredentialRepository credentials, AccessClientRepository clients,
                         AccessConsumerRepository consumers, AccessEntitlementRepository entitlements,
                         AccessGrantRepository grants, AccessSecrets secrets) {
        this.credentials = credentials;
        this.clients = clients;
        this.consumers = consumers;
        this.entitlements = entitlements;
        this.grants = grants;
        this.secrets = secrets;
    }

    /** Confirmed machine identity; fields come only from the server-side resolution. */
    public record AuthenticatedClient(String credentialId, String keyId, String clientId, String consumerId,
                                       String projectId, String environment, long clientPolicyRevision,
                                       long credentialPolicyRevision) {

        public boolean allows(String action) {
            return false;
        }
    }

    public AuthenticatedClient require(String authorization) {
        AccessSecrets.ParsedKey parsed = AccessSecrets.parse(authorization);
        if (parsed == null) {
            throw new UnauthorizedException("无效的中台凭证");
        }
        AccessCredential credential = credentials.findByKeyId(parsed.keyId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        String expected = credential.getSecretDigest();
        String actual = secrets.digest(parsed.keyId(), parsed.secret());
        if (!AccessSecrets.constantTimeEquals(expected, actual)) {
            throw new UnauthorizedException("无效的中台凭证");
        }
        Instant now = Instant.now();
        if (!credential.usableAt(now)) {
            throw new UnauthorizedException("凭证已失效");
        }
        AccessClient client = clients.findById(credential.getClientId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        if (!client.isActive()) {
            throw new UnauthorizedException("接入应用已停用");
        }
        AccessConsumer consumer = consumers.findById(client.getConsumerId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        if (!consumer.isActive()) {
            throw new UnauthorizedException("客户已停用");
        }
        return new AuthenticatedClient(credential.getId(), credential.getKeyId(), client.getId(),
                consumer.getId(), client.getProjectId(), client.getEnvironment(),
                client.getPolicyRevision(), credential.getPolicyRevision());
    }

    /**
     * Effective permission check: the credential must hold an active grant whose entitlement
     * is an active ceiling entry on the same Client. Empty grants deny by default.
     */
    public void requireAction(AuthenticatedClient who, String resourceKind, String resourceId, String action) {
        boolean allowed = grants.findAllByCredentialIdOrderByCreatedAtAsc(who.credentialId()).stream()
                .filter(AccessGrant::isActive)
                .map(AccessGrant::getEntitlementId)
                .map(entitlements::findById)
                .flatMap(java.util.Optional::stream)
                .anyMatch(entitlement -> entitlement.isActive()
                        && entitlement.getClientId().equals(who.clientId())
                        && entitlement.getResourceKind().equals(resourceKind)
                        && entitlement.getResourceId().equals(resourceId)
                        && entitlement.getAction().equals(action));
        if (!allowed) {
            throw new ForbiddenException("当前凭证未获准该操作");
        }
    }

    public Set<String> allowedActions(AuthenticatedClient who) {
        java.util.Set<String> result = new java.util.HashSet<>();
        grants.findAllByCredentialIdOrderByCreatedAtAsc(who.credentialId()).stream()
                .filter(AccessGrant::isActive)
                .map(AccessGrant::getEntitlementId)
                .map(entitlements::findById)
                .flatMap(java.util.Optional::stream)
                .filter(AccessEntitlement::isActive)
                .filter(entitlement -> entitlement.getClientId().equals(who.clientId()))
                .forEach(entitlement -> result.add(entitlement.getResourceKind() + ":" + entitlement.getResourceId() + ":" + entitlement.getAction()));
        return result;
    }

    /** Match a whole legacy token digest; used only by the legacy adapter, never as fallback of new keys. */
    public String legacyDigestOf(String wholeToken) {
        return secrets.legacyDigest(wholeToken);
    }
}
