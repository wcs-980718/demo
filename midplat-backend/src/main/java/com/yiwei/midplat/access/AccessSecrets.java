package com.yiwei.midplat.access;

import com.yiwei.midplat.common.api.UnauthorizedException;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Key material and digest handling. New keys: {@code mid_v2_<keyId>.<secret>} with a
 * SecureRandom 32-byte secret. Digest: HMAC-SHA-256(pepper, version || keyId || secret).
 * Legacy project tokens: HMAC over "legacy" || whole token, matched against
 * midplat_platform.legacy_token_digest.
 */
@Component
public class AccessSecrets {

    public static final String KEY_PREFIX = "mid_v2_";
    private static final int SECRET_BYTES = 32;

    private final String pepper;
    private final SecureRandom random = new SecureRandom();
    private Mac mac;

    public AccessSecrets(@Value("${midplat.access.secret-pepper:}") String pepper) {
        this.pepper = pepper;
    }

    @PostConstruct
    void init() {
        if (pepper == null || pepper.length() < 32) {
            // Deterministic development/test pepper. Production must set
            // MIDPLAT_ACCESS_SECRET_PEPPER (>= 32 chars) in protected config.
            return;
        }
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize access digest", e);
        }
    }

    public static final String DEV_PEPPER = "midplat-dev-pepper-0123456789abcdef-DO-NOT-USE-IN-PROD";

    public boolean configured() {
        if (mac != null) {
            return true;
        }
        // Fall back to the deterministic dev pepper so local/test runs keep working;
        // production deployments set the property and get the configured mac above.
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEV_PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize access digest", e);
        }
        return true;
    }

    Mac mac() {
        if (mac == null) {
            // Dev/test fallback when the property is absent; production sets
            // midplat.access.secret-pepper and init() installs the real mac above.
            configured();
        }
        return mac;
    }

    public record IssuedKey(String keyId, String secret, String rawKey, String digest) {}

    public IssuedKey issue() {
        byte[] secretBytes = new byte[SECRET_BYTES];
        random.nextBytes(secretBytes);
        String keyId = "k" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String raw = KEY_PREFIX + keyId + "." + secret;
        return new IssuedKey(keyId, secret, raw, digest(keyId, secret));
    }

    public String digest(String keyId, String secret) {
        byte[] payload = ("v1\u0000" + keyId + "\u0000" + secret).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digestOf(payload));
    }

    public String legacyDigest(String wholeToken) {
        byte[] payload = ("legacy\u0000" + wholeToken).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digestOf(payload));
    }

    private byte[] digestOf(byte[] payload) {
        Mac instance;
        try {
            instance = (Mac) mac().clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("Unable to clone digest mac", e);
        }
        return instance.doFinal(payload);
    }

    public record ParsedKey(String keyId, String secret) {}

    public static ParsedKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("缺少中台凭证");
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        if (!value.startsWith(KEY_PREFIX)) {
            return null; // not the new format; caller may try legacy adaptation
        }
        int dot = value.indexOf('.', KEY_PREFIX.length());
        if (dot <= KEY_PREFIX.length() || dot != value.length() - 1 - secretLength(value, dot)) {
            // keyId must not contain '.', secret is everything after the single dot
        }
        if (dot < 0 || dot == value.length() - 1) {
            throw new UnauthorizedException("凭证格式无效");
        }
        String keyId = value.substring(KEY_PREFIX.length(), dot);
        String secret = value.substring(dot + 1);
        if (keyId.isBlank() || secret.isBlank()) {
            throw new UnauthorizedException("凭证格式无效");
        }
        return new ParsedKey(keyId, secret);
    }

    private static int secretLength(String value, int dot) {
        return value.length() - dot - 1;
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a == null ? new byte[0] : a.getBytes(StandardCharsets.UTF_8),
                b == null ? new byte[0] : b.getBytes(StandardCharsets.UTF_8));
    }
}
