package com.yiwei.midplat.platform;

import com.yiwei.midplat.access.AccessAuthentication;
import com.yiwei.midplat.access.AccessAuthentication.AuthenticatedClient;
import com.yiwei.midplat.access.AccessSecrets;
import com.yiwei.midplat.common.api.UnauthorizedException;
import com.yiwei.midplat.platform.LegacyCredentialAdapter.LegacyIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for machine-caller authentication on runtime/open/report endpoints.
 * New keys resolve to an {@link AuthenticatedClient}; legacy project tokens go through
 * the protected-digest adapter only — a failed new-key lookup must never fall back to
 * plaintext token matching.
 */
@Service
public class PlatformCredentialService {

    private final ManagedPlatformRepository platforms;
    private final AccessAuthentication access;
    private final LegacyCredentialAdapter legacy;

    PlatformCredentialService(ManagedPlatformRepository platforms, AccessAuthentication access,
                              LegacyCredentialAdapter legacy) {
        this.platforms = platforms;
        this.access = access;
        this.legacy = legacy;
    }

    /** New-format authentication result. */
    @Transactional(readOnly = true)
    public AuthenticatedClient requireClient(String authorization) {
        return access.require(authorization);
    }

    /** Legacy token authentication via HMAC digest (see V34 migration + backfill). */
    @Transactional(readOnly = true)
    public LegacyIdentity requireLegacy(String authorization) {
        String token = extractToken(authorization);
        return legacy.requireByTokenDigest(token);
    }

    /** Project id of whichever caller form the key resolves to; for gateway project-ownership checks. */
    @Transactional(readOnly = true)
    public String requireCallerProjectId(String authorization) {
        Object who = require(authorization);
        if (who instanceof AuthenticatedClient client) {
            return client.projectId();
        }
        return ((LegacyIdentity) who).projectId();
    }

    /** Platform entity + resolved caller identity for run bridges that still key on the platform row. */
    @Transactional(readOnly = true)
    public CallerPlatform requireCallerManagedPlatform(String authorization) {
        Object who = require(authorization);
        if (who instanceof AuthenticatedClient client) {
            ManagedPlatform platform = platforms.findById(client.projectId())
                    .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
            return new CallerPlatform(platform, client.clientId(), client);
        }
        LegacyIdentity legacy = (LegacyIdentity) who;
        ManagedPlatform platform = platforms.findById(legacy.projectId())
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        return new CallerPlatform(platform, legacy.clientId(), null);
    }

    /** Platform row plus the confirmed machine identity that resolved to it. */
    public record CallerPlatform(ManagedPlatform platform, String clientId, AuthenticatedClient client) {}

    @Transactional(readOnly = true)
    public Object require(String authorization) {
        AccessSecrets.ParsedKey parsed = AccessSecrets.parse(authorization);
        if (parsed != null) {
            return access.require(authorization);
        }
        String token = extractToken(authorization);
        return legacy.requireByTokenDigest(token);
    }

    static String extractToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new UnauthorizedException("缺少中台凭证");
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        if (value.isBlank()) {
            throw new UnauthorizedException("缺少中台凭证");
        }
        return value;
    }
}
