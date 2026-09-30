package com.yiwei.midplat.platform;

import com.yiwei.midplat.access.AccessSecrets;
import com.yiwei.midplat.common.api.UnauthorizedException;
import org.springframework.stereotype.Service;

/**
 * Legacy project-token adapter. Tokens are located by HMAC digest over the whole token
 * (midplat_platform.legacy_token_digest), never by plaintext lookup. The plaintext column
 * is not read for authentication; it is cleared in W6 after all consumers migrate.
 */
@Service
public class LegacyCredentialAdapter {

    private final ManagedPlatformRepository platforms;
    private final AccessSecrets secrets;

    LegacyCredentialAdapter(ManagedPlatformRepository platforms, AccessSecrets secrets) {
        this.platforms = platforms;
        this.secrets = secrets;
    }

    /** Identity carried by a legacy token: the project's fixed legacy client. */
    public record LegacyIdentity(String projectId, String clientId) {}

    public LegacyIdentity requireByTokenDigest(String wholeToken) {
        String digest = secrets.legacyDigest(wholeToken);
        ManagedPlatform found = platforms.findByLegacyTokenDigest(digest)
                .orElseThrow(() -> new UnauthorizedException("无效的中台凭证"));
        return new LegacyIdentity(found.getId(), "legacy-client:" + found.getId());
    }
}
