package com.yiwei.midplat.capability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * W3 统一能力目录登记：模型/提示词正文与修订仍由各自主表和 catalog revision 承载，
 * 这里维护 midplat_capability 目录头（kind、来源三元组、状态、最新修订），创建幂等。
 */
@Service
public class CapabilityService {
    private final JdbcTemplate db;
    public CapabilityService(JdbcTemplate db){this.db=db;}

    @Transactional
    public void register(String kind,String sourceType,String sourceId,String name){
        int updated=db.update("update midplat_capability set name=?,updated_at=CURRENT_TIMESTAMP where source_system='midplat' and source_type=? and source_id=?",name,sourceType,sourceId);
        if(updated==0)db.update("insert into midplat_capability(id,kind,source_system,source_type,source_id,name,status) values(?,?,'midplat',?,?,?,'ACTIVE')",
            "cap-"+sourceType+"-"+sourceId,kind,sourceType,sourceId,name);
        db.update("update midplat_capability c set latest_revision_id=(select r.id from midplat_catalog_revision r where r.resource_type=c.source_type and r.resource_id=c.source_id order by r.created_at desc limit 1) where c.source_system='midplat' and c.source_type=? and c.source_id=?",sourceType,sourceId);
    }
}
