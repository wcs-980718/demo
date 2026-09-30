package com.yiwei.midplat.fusion;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
@RestController
@RequestMapping("/api/fusion-internal/model-invocations")
public class ModelInvocationController {
    private final ModelInvocationGateway gateway;
    public ModelInvocationController(ModelInvocationGateway gateway){this.gateway=gateway;}
    @PostMapping public void invoke(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody JsonNode body,HttpServletResponse response)throws IOException{gateway.invoke(auth,body,response);}
    @PostMapping("/cancel") public Object cancel(@RequestHeader(value="Authorization",required=false)String auth){return gateway.cancel(auth);}
}
