package com.yiwei.midplat.fusion;

import com.yiwei.midplat.common.api.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class FusionRunStreamController {
    private final FusionState state;private final FusionAgentClient agent;
    public FusionRunStreamController(FusionState state,FusionAgentClient agent){this.state=state;this.agent=agent;}
    @GetMapping("/api/fusion/projects/{project}/runs/{id}/stream")
    public ResponseEntity<StreamingResponseBody> stream(HttpServletRequest request,@PathVariable String project,@PathVariable String id,
            @RequestParam(defaultValue="development")String environment,@RequestParam(defaultValue="0")long after){
        FusionAccess.project(request,project,false);FusionController.check(project,environment);FusionController.identifier(id);
        if(after<0)throw new IllegalArgumentException("事件游标无效");
        var binding=state.binding(project,environment);if(binding==null||!"ready".equals(binding.get("status")))throw new ConflictException("项目尚未绑定");
        String deployment=(String)binding.get("deployment_id");var identity=FusionAccess.identity(request);
        agent.call(identity,project,environment,deployment,"run",Map.of("id",id));
        StreamingResponseBody body=output->{
            long cursor=after;
            try{
                long deadline=System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos();
                while(System.nanoTime()<deadline){
                    var events=agent.call(identity,project,environment,deployment,"events",Map.of("id",id,"after",cursor));
                    for(var event:events){cursor=event.path("sequence").asLong();output.write(("id: "+cursor+"\nevent: "+event.path("type").asText()+"\ndata: "+event.path("data").toString()+"\n\n").getBytes(StandardCharsets.UTF_8));}
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
                    var run=agent.call(identity,project,environment,deployment,"run",Map.of("id",id));
                    if(Set.of("succeeded","failed","cancelled").contains(run.path("status").asText())&&cursor>=run.path("last_event").asLong())break;
                    Thread.sleep(350);
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(IOException e){/* 观察连接断开仅移除观察者；业务运行继续，取消只由显式且已授权的动作触发。 */}
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(body);
    }
}
