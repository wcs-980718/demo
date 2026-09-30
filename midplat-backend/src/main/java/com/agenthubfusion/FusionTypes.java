package com.agenthubfusion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
public final class FusionTypes {
 private FusionTypes(){}
 /**
  * 运行/管理上下文。principal 来自中台认证，不由外部 body 任意指定；
  * migration 仅受控迁移服务身份可置位（资产主数据写入退役后唯一的合法写入通道）。
  */
 public record Context(String principal,String projectId,String environment,boolean writable,String clientId,boolean migration){
  public Context(String principal,String projectId,String environment,boolean writable){this(principal,projectId,environment,writable,"",false);}
  public Context(String principal,String projectId,String environment,boolean writable,String clientId){this(principal,projectId,environment,writable,clientId,false);}
  public String ownerClientId(){return clientId==null?"":clientId;}
 }
 public record Command(Context context,String action,String deploymentId,JsonNode body,String schemaVersion){
  public Command(Context context,String action,String deploymentId,JsonNode body){this(context,action,deploymentId,body,"w3");}
 }
 public record Deployment(String id,String projectId,String environment,String definitionId,String name,long revision,
                          JsonNode draft,String publishedReleaseId,String activeReleaseId,String pendingJobId,long activationRevision){}
 public record Release(String id,String deploymentId,long sequence,String note,String restoredFrom,String hash,
                       JsonNode snapshot,String createdAt){}
 public record Publication(String id,String deploymentId,String status,String error,String releaseId){}
 public record CatalogRequest(String projectId,String environment,String deploymentId,List<String> modelRevisionIds,List<String> promptRevisionIds){}
}
