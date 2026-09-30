package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.yiwei.midplat.common.api.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Execution credentials have a separate signing key and cannot authenticate management requests. */
@Component
public class ExecutionTickets {
    private final ObjectMapper json;
    private final String secret;
    public ExecutionTickets(ObjectMapper json, @Value("${midplat.fusion.execution-key:}") String secret,
            @Value("${midplat.fusion.execution-enabled:false}") boolean enabled) {
        this.json = json;
        this.secret = secret;
        if (enabled && secret.length() < 32) throw new IllegalStateException("Execution signing key must contain 32 characters");
    }
    public JsonNode verify(String authorization) {
        try {
            if (secret.length() < 32 || authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 20000) throw new Exception();
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 2) throw new Exception();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)), Base64.getUrlDecoder().decode(parts[1]))) throw new Exception();
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            long now = Instant.now().getEpochSecond();
            if (!"fusion-model-v1".equals(claims.path("aud").asText()) || !claims.path("exp").isIntegralNumber()
                    || claims.path("exp").asLong() <= now || claims.path("exp").asLong() > now + 660
                    || !claims.path("iat").isIntegralNumber() || claims.path("iat").asLong() > now + 10
                    || claims.path("iat").asLong() < now - 660) throw new Exception();
            for (String key : new String[]{"projectId", "deploymentId", "releaseId", "runId"}) FusionController.identifier(claims.path(key).asText());
            FusionController.check(claims.path("projectId").asText(), claims.path("environment").asText());
            if (claims.path("principal").asText().isBlank() || claims.path("principal").asText().length() > 128 || !claims.path("nodes").isObject()) throw new Exception();
            return claims;
        } catch (Exception e) { throw new UnauthorizedException("执行票据无效、过期或签名不匹配"); }
    }
}
