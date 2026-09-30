package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.platform.PlatformCredentialService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runtime/agent")
public class BusinessModelController {
    private final PlatformCredentialService credentials;
    private final BusinessModelService service;
    public BusinessModelController(PlatformCredentialService credentials, BusinessModelService service) {
        this.credentials=credentials;this.service=service;
    }
    @PostMapping("/tasks/{taskKey}/v1/chat/completions")
    public void chat(@RequestHeader(value="Authorization",required=false) String authorization,
                     @RequestHeader(value="X-Request-Id",required=false) String requestId,
                     @RequestHeader(value="X-Request-Deadline-Ms",required=false) Long deadlineMillis,
                     @RequestHeader(value="X-Request-Timeout-Ms",required=false) Long timeoutMillis,
                     @PathVariable String taskKey, @RequestBody JsonNode request,
                     HttpServletResponse response) throws IOException {
        var caller=credentials.requireCallerManagedPlatform(authorization);
        service.chat(caller.platform(),caller.clientId(),taskKey,requestId,request,response,deadlineMillis,timeoutMillis);
    }
    @GetMapping("/configuration")
    public Object configuration(@RequestHeader(value="Authorization",required=false) String authorization) {
        var caller=credentials.requireCallerManagedPlatform(authorization);
        return service.configuration(caller.platform(),caller.clientId());
    }
}
