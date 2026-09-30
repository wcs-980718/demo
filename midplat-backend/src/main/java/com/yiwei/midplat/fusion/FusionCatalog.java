package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.*;
import com.yiwei.midplat.model.AiModel;
import com.yiwei.midplat.prompt.Prompt;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FusionCatalog {
    private final CatalogRevisionRepository revisions;
    private final ObjectMapper json;
    public FusionCatalog(CatalogRevisionRepository revisions,ObjectMapper json){this.revisions=revisions;this.json=json;}
    public void capture(AiModel model){
        capture("model",model.getId(),Map.of("name",model.getName(),"kind",model.getKind().name(),"model",model.getModelName(),
                "baseUrl",model.getBaseUrl(),"credentialRef",model.getId(),"capabilities",new com.yiwei.midplat.model.ModelService.ModelCapabilities(model.getSupportsToolCalls(), model.getSupportsJsonObject())));
    }
    public void capture(Prompt prompt){
        capture("prompt",prompt.getId(),Map.of("name",prompt.getName(),"slot",prompt.getSlot(),"version",prompt.getVersionName(),"body",prompt.getBody()));
    }
    private void capture(String type,String resourceId,Map<String,Object> body){
        try {
            String content=json.writeValueAsString(new TreeMap<>(body));
            String hash=hash(content);
            if(revisions.findByResourceTypeAndResourceIdAndContentHash(type,resourceId,hash).isEmpty())
                revisions.save(new CatalogRevision(UUID.randomUUID().toString(),type,resourceId,hash,content));
        }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Unable to snapshot catalog",ex);}
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String type){return revisions.findByResourceTypeOrderByCreatedAtDesc(type).stream().map(this::view).toList();}
    @Transactional(readOnly=true)
    public Map<String,Object> require(String id,String type){
        CatalogRevision r=revisions.findById(id).filter(x->x.getResourceType().equals(type)).orElseThrow(()->new ResourceNotFoundException("目录修订不存在"));
        return view(r);
    }
    private Map<String,Object> view(CatalogRevision r){
        try {
            Map<String,Object> out=new LinkedHashMap<>();out.put("id",r.getId());out.put("resourceId",r.getResourceId());
            out.put("hash",r.getContentHash());out.put("createdAt",r.getCreatedAt());out.put("content",json.readTree(r.getContent()));return out;
        }catch(Exception ex){throw new IllegalStateException("Catalog revision is corrupt",ex);}
    }
    public static String hash(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
