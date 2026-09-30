package com.yiwei.midplat.fusion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.yiwei.midplat.common.api.ConflictException;
@Component
public class FusionOwnership {
 private final JdbcTemplate db;
 @org.springframework.beans.factory.annotation.Value("${midplat.fusion.block-legacy-outbound:false}") private boolean blockOutbound;
 public FusionOwnership(JdbcTemplate db){this.db=db;}
 public void requireOutboundAllowed(){if(blockOutbound)throw new ConflictException("融合开发环境暂未开放旧执行与运行配置下发，请使用融合项目草稿");}
 public boolean manages(String project){return db.queryForObject("select count(*) from midplat_project_agent_binding where project_id=?",Long.class,project)>0;}
 public void requireLegacyWritable(String project){if(manages(project))throw new ConflictException("该项目配置已由融合部署管理，请修改部署草稿并发布");}
 public boolean references(String type,String resource){return db.queryForObject("select count(*) from midplat_catalog_reference x join midplat_catalog_revision r on r.id=x.revision_id where r.resource_type=? and r.resource_id=?",Long.class,type,resource)>0;}
}
