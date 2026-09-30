package com.yiwei.midplat.fusion;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/fusion-internal")
public class FusionInternalController {
 private final FusionState state;
 public FusionInternalController(FusionState state){this.state=state;}
 public record Resolve(String projectId,String environment,String deploymentId,List<String> modelRevisionIds,List<String> promptRevisionIds){}
 @PostMapping("/catalog/resolve") public Object resolve(@RequestBody Resolve r){
  FusionController.check(r.projectId(),r.environment());FusionController.identifier(r.deploymentId());
  if(r.modelRevisionIds()==null||r.promptRevisionIds()==null)throw new IllegalArgumentException("修订引用不能为空");
  return state.resolve(r.projectId(),r.environment(),r.deploymentId(),r.modelRevisionIds(),r.promptRevisionIds());
 }
}
