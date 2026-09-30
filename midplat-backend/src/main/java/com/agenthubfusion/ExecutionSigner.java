package com.agenthubfusion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExecutionSigner {
    private final ObjectMapper json;
    private final String secret;
    private final boolean enabled;
    public ExecutionSigner(ObjectMapper json, @Value("${fusion.execution-key:}") String secret,
            @Value("${fusion.execution-enabled:false}") boolean enabled) {
        this.json=json;this.secret=secret;this.enabled=enabled;
        if(enabled&&secret.length()<32)throw new IllegalStateException("Execution signing key must contain at least 32 characters");
    }
    public boolean enabled(){return enabled;}
    public String sign(FusionTypes.Context context,String deployment,String release,String run,JsonNode task){
        if(!enabled)throw new FusionFault(503,"执行服务未启用");
        try{
            long now=Instant.now().getEpochSecond();
            ObjectNode claims=json.createObjectNode().put("aud","fusion-model-v1").put("iat",now).put("exp",now+600)
                .put("projectId",context.projectId()).put("environment",context.environment()).put("principal",context.principal())
                .put("deploymentId",deployment).put("releaseId",release).put("runId",run).put("clientId",context.ownerClientId());
            ObjectNode nodes=claims.putObject("nodes");
            claims.set("assets",task.has("assetRevisionIds")?task.path("assetRevisionIds").deepCopy():json.createArrayNode());
            if(task.path("nodes").isEmpty())nodes.put("task",task.path("modelRevisionId").asText());
            else for(JsonNode node:task.path("nodes"))if(WorkflowGraph.isModel(node))nodes.put(node.path("id").asText(),node.path("modelRevisionId").asText());
            String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        }catch(FusionFault e){throw e;}catch(Exception e){throw new FusionFault(500,"无法签发执行票据");}
    }
    public JsonNode verify(String authorization){
        try{
            if(!enabled||authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>20000)throw new Exception();
            String[] parts=authorization.substring(7).split("\\.",-1);if(parts.length!=2)throw new Exception();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!java.security.MessageDigest.isEqual(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)),Base64.getUrlDecoder().decode(parts[1])))throw new Exception();
            JsonNode claims=json.readTree(Base64.getUrlDecoder().decode(parts[0]));long now=Instant.now().getEpochSecond();
            if(!claims.path("aud").asText().equals("fusion-model-v1")||claims.path("exp").asLong()<=now||claims.path("exp").asLong()>now+660)throw new Exception();return claims;
        }catch(Exception e){throw new FusionFault(401,"执行票据无效或过期");}
    }
}
