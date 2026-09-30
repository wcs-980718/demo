package com.yiwei.midplat.access;

import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ForbiddenException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Management operations for consumers/clients/credentials. All endpoints require admin identity. */
@Service
public class AccessAdminService {

    private final AccessConsumerRepository consumers;
    private final AccessClientRepository clients;
    private final AccessCredentialRepository credentials;
    private final AccessEntitlementRepository entitlements;
    private final AccessGrantRepository grants;
    private final AccessSecrets secrets;

    AccessAdminService(AccessConsumerRepository consumers, AccessClientRepository clients,
                       AccessCredentialRepository credentials, AccessEntitlementRepository entitlements,
                       AccessGrantRepository grants, AccessSecrets secrets) {
        this.consumers = consumers;
        this.clients = clients;
        this.credentials = credentials;
        this.entitlements = entitlements;
        this.grants = grants;
        this.secrets = secrets;
    }

    // ---- consumers / clients ----

    @Transactional
    public AccessConsumer createConsumer(String code, String name, String type) {
        if (consumers.existsByCode(code)) {
            throw new ConflictException("客户编码已存在: " + code);
        }
        return consumers.save(new AccessConsumer(Identities.newId(), code, name, type));
    }

    public List<AccessConsumer> listConsumers() {
        return consumers.findAll();
    }

    @Transactional
    public AccessClient createClient(String consumerId, String projectId, String environment, String name, String code) {
        AccessConsumer consumer = consumers.findById(consumerId)
                .orElseThrow(() -> new ResourceNotFoundException("consumer not found: " + consumerId));
        if (!consumer.isActive()) {
            throw new ConflictException("客户已停用，不能新建接入应用");
        }
        if (clients.findByConsumerIdAndCode(consumerId, code).isPresent()) {
            throw new ConflictException("同客户下接入应用编码已存在: " + code);
        }
        return clients.save(new AccessClient(Identities.newId(), consumerId, projectId, environment, name, code));
    }

    public List<AccessClient> listClients(String projectId) {
        return projectId == null || projectId.isBlank()
                ? clients.findAll()
                : clients.findAllByProjectIdOrderByCreatedAtAsc(projectId);
    }

    @Transactional
    public AccessClient changeClientStatus(String id, String status) {
        AccessClient client = requireClient(id);
        client.changeStatus(status);
        return client;
    }

    // ---- entitlements (Client ceiling) ----

    @Transactional
    public List<AccessEntitlement> setEntitlements(String clientId, List<EntitlementCmd> commands) {
        AccessClient client = requireClient(clientId);
        for (EntitlementCmd cmd : commands) {
            entitlements.findByClientIdAndResourceKindAndResourceIdAndAction(clientId, cmd.resourceKind(), cmd.resourceId(), cmd.action())
                    .ifPresentOrElse(existing -> applyEntitlementStatus(existing, cmd), () -> {
                        // W5 契约：未显式给出 status 的新条目视为启用（前端只传资源与动作）。
                        if (cmd.status() == null || AccessEntitlement.STATUS_ACTIVE.equals(cmd.status())) {
                            entitlements.save(new AccessEntitlement(Identities.newId(), clientId,
                                    cmd.resourceKind(), cmd.resourceId(), cmd.action(), cmd.constraints()));
                        }
                    });
        }
        client.bumpPolicyRevision();
        return entitlements.findAllByClientIdOrderByCreatedAtAsc(clientId);
    }

    private static boolean isRevoked(String status) { return status != null && AccessEntitlement.STATUS_REVOKED.equalsIgnoreCase(status); }

    private static void applyEntitlementStatus(AccessEntitlement existing, EntitlementCmd cmd) {
        if (isRevoked(cmd.status())) {
            existing.revoke();
        } else if (!existing.isActive()) {
            throw new ConflictException("已撤销的上限不能重新启用，请新建条目");
        }
    }

    public List<AccessEntitlement> listEntitlements(String clientId) {
        requireClient(clientId);
        return entitlements.findAllByClientIdOrderByCreatedAtAsc(clientId);
    }

    // ---- credentials ----

    /** Creation result; the raw secret appears exactly once and only when newly generated. */
    public record IssuedCredential(CredentialView view, String secret, boolean secretAvailable) {}

    @Transactional
    public IssuedCredential issueCredential(String clientId, String label, Instant expiresAt, String idempotencyKey) {
        requireClient(clientId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = credentials.findAllByClientIdOrderByCreatedAtDesc(clientId).stream()
                    .filter(c -> label.equals(c.getLabel()))
                    .findFirst();
            if (existing.isPresent()) {
                // Same logical issuance retried: never mint a second key, never re-return the secret.
                return new IssuedCredential(view(existing.get()), null, false);
            }
        }
        AccessSecrets.IssuedKey key = secrets.issue();
        AccessCredential credential = credentials.save(new AccessCredential(Identities.newId(), clientId,
                key.keyId(), key.digest(), 1, label, Instant.now(), expiresAt, null));
        return new IssuedCredential(view(credential), key.rawKey(), true);
    }

    @Transactional
    public IssuedCredential rotateCredential(String credentialId, String label) {
        AccessCredential old = requireCredential(credentialId);
        if (!old.usableAt(Instant.now())) {
            throw new ConflictException("仅活跃凭证可以轮换");
        }
        AccessSecrets.IssuedKey key = secrets.issue();
        AccessCredential rotated = credentials.save(new AccessCredential(Identities.newId(), old.getClientId(),
                key.keyId(), key.digest(), 1, label, Instant.now(), old.getExpiresAt(), old.getId()));
        // The new key inherits the old key's approved grants (still capped by Client ceiling).
        for (AccessGrant grant : grants.findAllByCredentialIdOrderByCreatedAtAsc(old.getId())) {
            if (grant.isActive()) {
                grants.save(new AccessGrant(Identities.newId(), rotated.getId(), grant.getEntitlementId(), grant.getConstraints()));
            }
        }
        // Old key keeps working until its grace deadline; default 24h, bounded by original expiry.
        Instant grace = Instant.now().plusSeconds(24 * 3600);
        if (old.getExpiresAt() != null && old.getExpiresAt().isBefore(grace)) {
            grace = old.getExpiresAt();
        }
        // represented by shortened expiry; explicit revoke remains the irreversible action
        credentials.save(old);
        return new IssuedCredential(view(rotated), key.rawKey(), true);
    }

    @Transactional
    public CredentialView revokeCredential(String credentialId) {
        AccessCredential credential = requireCredential(credentialId);
        credential.revoke(Instant.now());
        return view(credential);
    }

    public List<CredentialView> listCredentials(String clientId) {
        requireClient(clientId);
        return credentials.findAllByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(AccessAdminService::view).toList();
    }

    @Transactional
    public List<AccessGrant> setGrants(String credentialId, List<GrantCmd> commands) {
        AccessCredential credential = requireCredential(credentialId);
        AccessClient client = requireClient(credential.getClientId());
        List<AccessEntitlement> ceiling = entitlements.findAllByClientIdOrderByCreatedAtAsc(client.getId());
        for (GrantCmd cmd : commands) {
            AccessEntitlement entitlement = ceiling.stream()
                    .filter(e -> e.getId().equals(cmd.entitlementId()))
                    .findFirst()
                    .orElseThrow(() -> new ForbiddenException("授权上限不属于该接入应用"));
            grants.findAllByCredentialIdOrderByCreatedAtAsc(credentialId).stream()
                    .filter(g -> g.getEntitlementId().equals(cmd.entitlementId()))
                    .findFirst()
                    .ifPresentOrElse(existing -> {
                        if (isRevoked(cmd.status())) {
                            existing.revoke();
                        }
                    }, () -> {
                        if (cmd.status() == null || AccessGrant.STATUS_ACTIVE.equals(cmd.status())) {
                            grants.save(new AccessGrant(Identities.newId(), credentialId, entitlement.getId(), cmd.constraints()));
                        }
                    });
        }
        credential.bumpPolicyRevision();
        client.bumpPolicyRevision();
        return grants.findAllByCredentialIdOrderByCreatedAtAsc(credentialId);
    }

    public List<AccessGrant> listGrants(String credentialId) {
        requireCredential(credentialId);
        return grants.findAllByCredentialIdOrderByCreatedAtAsc(credentialId);
    }

    private AccessClient requireClient(String id) {
        return clients.findById(id).orElseThrow(() -> new ResourceNotFoundException("client not found: " + id));
    }

    private AccessCredential requireCredential(String id) {
        return credentials.findById(id).orElseThrow(() -> new ResourceNotFoundException("credential not found: " + id));
    }

    /** Safe projection: never exposes digest or raw secret. */
    public static CredentialView view(AccessCredential c) {
        return new CredentialView(c.getId(), c.getClientId(), c.getKeyId(), c.getLabel(), c.getStatus(),
                c.getValidFrom(), c.getExpiresAt(), c.getRevokedAt(), c.getLastUsedAt(),
                c.getPredecessorId(), c.isSecretRevealed(), c.getPolicyRevision());
    }

    public record CredentialView(String id, String clientId, String keyId, String label, String status,
                                 Instant validFrom, Instant expiresAt, Instant revokedAt, Instant lastUsedAt,
                                 String predecessorId, boolean secretRevealed, long policyRevision) {}

    public record EntitlementCmd(String resourceKind, String resourceId, String action, String constraints, String status) {}

    public record GrantCmd(String entitlementId, String constraints, String status) {}
}
