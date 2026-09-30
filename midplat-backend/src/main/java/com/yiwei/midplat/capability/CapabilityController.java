package com.yiwei.midplat.capability;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.fusion.FusionAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * W3 统一能力目录：模型/提示词正文仍由各自主表承载，这里提供目录头、修订与引用视图。
 * 位于管理认证域内（见 FusionAccess.shouldNotFilter），机器凭证不可访问。
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {
    private final JdbcTemplate db;
    public CapabilityController(JdbcTemplate db){this.db=db;}

    @GetMapping
    ApiResponse<List<Map<String,Object>>> list(HttpServletRequest request,@RequestParam(required=false) String kind,@RequestParam(required=false) String search){
        FusionAccess.admin(request);
        StringBuilder sql=new StringBuilder("select c.id,c.kind,c.source_system,c.source_type,c.source_id,c.name,c.status,c.latest_revision_id,c.updated_at,"+
            "(select count(*) from midplat_catalog_reference r join midplat_catalog_revision v on v.id=r.revision_id where v.resource_type=c.source_type and v.resource_id=c.source_id) as reference_count "+
            "from midplat_capability c where 1=1");
        var args=new ArrayList<Object>();
        if(kind!=null&&!kind.isBlank()&&!"all".equals(kind)){sql.append(" and c.kind=?");args.add(kind);}
        if(search!=null&&!search.isBlank()){sql.append(" and lower(c.name) like ?");args.add("%"+search.toLowerCase(Locale.ROOT)+"%");}
        sql.append(" order by c.kind,c.name");
        return ApiResponse.ok(db.queryForList(sql.toString(),args.toArray()));
    }

    @GetMapping("/{id:^(?!assets$|projects$).+}")
    ApiResponse<Map<String,Object>> detail(HttpServletRequest request,@PathVariable String id){
        FusionAccess.admin(request);
        identifier(id);
        var rows=db.queryForList("select id,kind,source_system,source_type,source_id,name,status,latest_revision_id,created_at,updated_at from midplat_capability where id=?",id);
        if(rows.isEmpty())throw new ResourceNotFoundException("能力不存在");
        Map<String,Object> detail=new LinkedHashMap<>(rows.get(0));
        var head=(Map<String,Object>)detail;
        String type=(String)head.get("source_type"),source=(String)head.get("source_id");
        detail.put("revisions",db.queryForList("select id,content_hash,created_at from midplat_catalog_revision where resource_type=? and resource_id=? order by created_at desc",type,source));
        detail.put("references",db.queryForList("select r.deployment_id,r.revision_id,v.created_at as revision_created_at from midplat_catalog_reference r join midplat_catalog_revision v on v.id=r.revision_id where v.resource_type=? and v.resource_id=? order by v.created_at desc limit 50",type,source));
        return ApiResponse.ok(detail);
    }

    /** 旧项目资源授权接口已退役；不影响客户凭证的授权与访问控制。 */
    @GetMapping("/grants/{projectId}")
    ApiResponse<List<Map<String,Object>>> grants(HttpServletRequest request,@PathVariable String projectId,@RequestParam(defaultValue="development") String environment){
        FusionAccess.admin(request);
        identifier(projectId);
        throw new com.yiwei.midplat.fusion.FusionUpstreamFault(410,"项目能力授权已取消，请直接配置智能体模型与规则提示词");
    }

    static void identifier(String id){if(id==null||!id.matches("[a-zA-Z0-9_-]{1,192}"))throw new IllegalArgumentException("标识格式无效");}
}
