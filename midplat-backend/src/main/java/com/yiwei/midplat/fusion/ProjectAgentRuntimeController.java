package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.platform.PlatformCredentialService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/**
 * W1: machine callers resolve through the unified credential service. New keys carry a
 * stable clientId; legacy tokens resolve to the project's legacy client. W2 will route
 * these identities through the run/session ownership checks in the agent service.
 */
@RestController
@RequestMapping("/api/runtime/agent")
public class ProjectAgentRuntimeController {
    @org.springframework.beans.factory.annotation.Autowired private ModelInvocationGateway modelGateway;
    private final PlatformCredentialService credentials;private final FusionRuntimeBridge bridge;
    public ProjectAgentRuntimeController(PlatformCredentialService credentials,FusionRuntimeBridge bridge){this.credentials=credentials;this.bridge=bridge;}
    @PostMapping("/runs") public Object start(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody JsonNode body){return ResponseEntity.accepted().body(bridge.command(caller(auth),"startRun",body));}
    @GetMapping("/runs/{id}") public Object run(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){FusionController.identifier(id);return bridge.command(caller(auth),"run",Map.of("id",id));}
    @GetMapping("/runs/{id}/events") public Object events(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestParam(defaultValue="0")long after){FusionController.identifier(id);return bridge.command(caller(auth),"events",Map.of("id",id,"after",after));}
    @PostMapping("/runs/{id}/cancel") public Object cancel(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){FusionController.identifier(id);Object result=bridge.command(caller(auth),"cancelRun",Map.of("id",id));modelGateway.cancelAuthorizedRun(id);return result;}
    @PostMapping("/sessions/{id}/close") public Object closeSession(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){FusionController.identifier(id);return bridge.command(caller(auth),"closeSession",Map.of("id",id));}
    private com.yiwei.midplat.platform.PlatformCredentialService.CallerPlatform caller(String authorization){
        return credentials.requireCallerManagedPlatform(authorization);
    }
}
