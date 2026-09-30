package com.yiwei.midplat.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.fusion.FusionAccess;
import com.yiwei.midplat.fusion.FusionAgentClient;
import com.yiwei.midplat.fusion.FusionCatalog;
import com.yiwei.midplat.fusion.FusionUpstreamFault;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 能力资产管理闭环（W5）：skill/tool/knowledge/data 四类资产的正文主数据保存在中台
 * midplat_capability_asset(_revision)，修订不可变、更新走 CAS；项目授权保存在
 * midplat_capability_asset_grant；智能体侧镜像仅通过 FusionAgentClient 的受控迁移服务
 * 通道写入，状态记录在 midplat_capability_asset_sync。任何数据库事务都不跨越远程请求，
 * 远程失败记录 FAILED 并可重试，绝不降级为“成功”。
 */
@Service
public class CapabilityAssetService {
    public static final Set<String> KINDS = Set.of("skill", "tool", "knowledge", "data");
    private static final Set<String> CREATE_STATUSES = Set.of("DRAFT", "ACTIVE");
    private static final Set<String> TARGET_STATUSES = Set.of("ACTIVE", "DISABLED", "ARCHIVED");
    private static final Set<String> SKILL_FIELDS = Set.of("body");
    // secretRef 只是部署配置引用，不是秘密本身；headers/apiKey/secret/token 一律拒绝。
    private static final Set<String> TOOL_FIELDS = Set.of("toolName", "description", "url", "method", "readOnly", "parameters", "secretRef");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("documents");
    private static final Set<String> DOCUMENT_ITEM_FIELDS = Set.of("title", "text");
    private static final int MAX_CONTENT = 150000;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final FusionAgentClient agent;
    private final TransactionTemplate tx;

    public CapabilityAssetService(JdbcTemplate db, ObjectMapper json, FusionAgentClient agent, PlatformTransactionManager manager) {
        this.db = db; this.json = json; this.agent = agent; this.tx = new TransactionTemplate(manager);
    }

    // ==================== 查询 ====================

    /** 分页列表：四类资产查主数据表；model/prompt 返回目录头摘要，正文仍由各自主域承载。“全部”合并六类。 */
    public Map<String, Object> page(String kind, String status, String search, boolean includeCatalog, long page, long pageSize) {
        long safeSize = Math.min(Math.max(pageSize, 1), 100);
        long safePage = Math.max(page, 0);
        String normalizedKind = kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind) ? null : kind.toLowerCase(Locale.ROOT);
        if (normalizedKind != null && !KINDS.contains(normalizedKind) && !Set.of("model", "prompt").contains(normalizedKind))
            throw bad("不支持的资产类型");
        String normalizedStatus = null;
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            normalizedStatus = status.toUpperCase(Locale.ROOT);
            if (!Set.of("DRAFT", "ACTIVE", "DISABLED", "ARCHIVED").contains(normalizedStatus)) throw bad("状态筛选无效");
        }
        String needle = search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
        if (!includeCatalog && ("model".equals(normalizedKind) || "prompt".equals(normalizedKind))) throw bad("当前筛选不包含模型/提示词目录摘要");
        boolean catalogOnly = includeCatalog && ("model".equals(normalizedKind) || "prompt".equals(normalizedKind));
        boolean skipCatalog = !includeCatalog || (normalizedKind != null && KINDS.contains(normalizedKind));
        String assetWhere = " where 1=1";
        List<Object> assetArgs = new ArrayList<>();
        if (normalizedKind != null && KINDS.contains(normalizedKind)) { assetWhere += " and a.kind=?"; assetArgs.add(normalizedKind); }
        if (normalizedStatus != null) { assetWhere += " and a.status=?"; assetArgs.add(normalizedStatus); }
        if (needle != null) { assetWhere += " and lower(a.name) like ?"; assetArgs.add("%" + needle + "%"); }
        String catalogWhere = " where c.kind in ('model','prompt')";
        List<Object> catalogArgs = new ArrayList<>();
        if (catalogOnly) { catalogWhere = " where c.kind=?"; catalogArgs.add(normalizedKind); }
        if (normalizedStatus != null) { catalogWhere += " and c.status=?"; catalogArgs.add(normalizedStatus); }
        if (needle != null) { catalogWhere += " and lower(c.name) like ?"; catalogArgs.add("%" + needle + "%"); }
        String assetSelect = "select a.id,a.kind,a.name,a.description,a.status,a.revision,a.current_revision_id,r.content_hash,a.created_at,a.updated_at," +
            "(select count(*) from midplat_capability_asset_grant g where g.asset_id=a.id and g.enabled=true) as grant_count," +
            "(select count(*) from midplat_capability_asset_sync s where s.asset_id=a.id and s.status='SYNCED' and s.enabled=true) as synced_count," +
            "(select count(*) from midplat_capability_asset_sync s where s.asset_id=a.id and s.status='FAILED') as failed_count," +
            "(select count(*) from midplat_capability_asset_sync s where s.asset_id=a.id and s.status='PENDING') as pending_count " +
            "from midplat_capability_asset a left join midplat_capability_asset_revision r on r.id=a.current_revision_id" + assetWhere;
        String catalogSelect = "select c.id,c.kind,c.name,cast(null as varchar) as description,c.status,cast(null as bigint) as revision,c.latest_revision_id as current_revision_id," +
            "cast(null as varchar) as content_hash,c.created_at,c.updated_at," +
            "(select count(*) from midplat_catalog_reference r join midplat_catalog_revision v on v.id=r.revision_id where v.resource_type=c.source_type and v.resource_id=c.source_id) as grant_count," +
            "0 as synced_count,0 as failed_count,0 as pending_count from midplat_capability c" + catalogWhere;
        String from;
        List<Object> args = new ArrayList<>();
        if (catalogOnly) { from = "(" + catalogSelect + ") x"; args.addAll(catalogArgs); }
        else if (skipCatalog) { from = "(" + assetSelect + ") x"; args.addAll(assetArgs); }
        else { from = "(" + assetSelect + " union all " + catalogSelect + ") x"; args.addAll(assetArgs); args.addAll(catalogArgs); }
        long total = db.queryForObject("select count(*) from " + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add(safePage * safeSize);
        var rows = db.queryForList("select * from " + from + " order by updated_at desc,id limit ? offset ?", pageArgs.toArray());
        return pageEnvelope(rows.stream().map(this::summaryRow).toList(), total, safePage, safeSize);
    }

    public Map<String, Object> detail(String id) {
        identifier(id, 192);
        var rows = db.queryForList("select a.*,r.content,r.content_hash from midplat_capability_asset a " +
            "left join midplat_capability_asset_revision r on r.id=a.current_revision_id where a.id=?", id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("能力资产不存在");
        Map<String, Object> head = rows.get(0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", head.get("id"));
        detail.put("kind", head.get("kind"));
        detail.put("name", head.get("name"));
        detail.put("description", head.get("description"));
        detail.put("status", head.get("status"));
        detail.put("revision", number(head.get("revision")));
        detail.put("currentRevisionId", head.get("current_revision_id"));
        detail.put("hash", head.get("content_hash"));
        detail.put("content", head.get("content") == null ? null : parse((String) head.get("content")));
        detail.put("createdBy", head.get("created_by"));
        detail.put("createdAt", head.get("created_at"));
        detail.put("updatedAt", head.get("updated_at"));
        detail.put("revisions", revisions(id));
        detail.put("grants", db.queryForList("select g.id,g.asset_id,a2.kind,a2.name as asset_name,g.project_id,p.name as project_name,g.environment,g.operation," +
            "g.version_rule,g.revision_id,g.enabled,g.policy_revision,g.updated_at from midplat_capability_asset_grant g " +
            "join midplat_capability_asset a2 on a2.id=g.asset_id join midplat_platform p on p.id=g.project_id " +
            "where g.asset_id=? order by g.project_id,g.environment,g.operation", id).stream().map(this::grantView).toList());
        detail.put("sync", db.queryForList("select s.*,r.revision as revision_number from midplat_capability_asset_sync s " +
            "join midplat_capability_asset_revision r on r.id=s.revision_id where s.asset_id=? order by s.project_id,s.environment", id)
            .stream().map(this::syncView).toList());
        long grantCount = db.queryForObject("select count(*) from midplat_capability_asset_grant where asset_id=? and enabled=true", Long.class, id);
        detail.put("grantCount", grantCount);
        return detail;
    }

    public List<Map<String, Object>> revisions(String id) {
        identifier(id, 192);
        requireAsset(id);
        return db.queryForList("select id,revision,note,created_by,created_at,content_hash from midplat_capability_asset_revision " +
            "where asset_id=? order by revision desc", id).stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.get("id")); view.put("revision", number(row.get("revision")));
            view.put("hash", row.get("content_hash")); view.put("note", row.get("note"));
            view.put("createdBy", row.get("created_by")); view.put("createdAt", row.get("created_at"));
            return view;
        }).toList();
    }

    // ==================== 变更（本地事务 + 提交后镜像同步） ====================

    public Map<String, Object> create(FusionAccess.Identity who, JsonNode body) {
        String kind = required(body, "kind", 16).toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw bad("不支持的资产类型");
        String name = required(body, "name", 128);
        String description = optional(body, "description", 500);
        String status = body.path("status").asText("DRAFT").toUpperCase(Locale.ROOT);
        if (!CREATE_STATUSES.contains(status)) throw bad("创建状态仅支持 draft 或 active");
        Stored stored = validateContent(kind, body.path("content"));
        String assetId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        String principal = who == null ? null : who.principal();
        tx.executeWithoutResult(status0 -> {
            db.update("insert into midplat_capability_asset(id,kind,name,description,status,current_revision_id,revision,version,owner_workspace_id,created_by,created_at,updated_at)" +
                " values(?,?,?,?,?,?,1,1,'internal',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", assetId, kind, name, description, status, revisionId, principal);
            insertRevision(assetId, revisionId, 1, stored, null, principal);
            registerCatalogHead(assetId, kind, name, status, revisionId);
        });
        Map<String, Object> result = new LinkedHashMap<>(detail(assetId));
        result.put("sync", List.of());
        return result;
    }

    public Map<String, Object> update(FusionAccess.Identity who, String id, JsonNode body) {
        identifier(id, 192);
        if (!body.path("expectedRevision").isIntegralNumber()) throw bad("缺少 expectedRevision");
        long expected = body.path("expectedRevision").asLong();
        String note = optional(body, "note", 500);
        String name = body.hasNonNull("name") ? required(body, "name", 128) : null;
        String description = body.has("description") ? optional(body, "description", 500) : null;
        Map<String, Object> head = requireAsset(id);
        Stored stored = validateContent((String) head.get("kind"), body.path("content"));
        String revisionId = UUID.randomUUID().toString();
        String principal = who == null ? null : who.principal();
        tx.executeWithoutResult(status0 -> {
            var locked = db.queryForList("select kind,name,description,status,revision from midplat_capability_asset where id=? for update", id);
            if (locked.isEmpty()) throw new ResourceNotFoundException("能力资产不存在");
            Map<String, Object> row = locked.get(0);
            if ("ARCHIVED".equals(row.get("status"))) throw bad("已归档资产不可编辑");
            if (((Number) row.get("revision")).longValue() != expected) throw new ConflictException("资产已更新，请刷新后重试");
            String effectiveName = name == null ? (String) row.get("name") : name;
            insertRevision(id, revisionId, expected + 1, stored, note, principal);
            db.update("update midplat_capability_asset set name=?,description=?,current_revision_id=?,revision=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?",
                effectiveName, description == null ? row.get("description") : description, revisionId, expected + 1, id);
            registerCatalogHead(id, (String) row.get("kind"), effectiveName, (String) row.get("status"), revisionId);
        });
        Map<String, Object> result = new LinkedHashMap<>(detail(id));
        result.put("sync", syncEnabledGrants(who, id, true));
        return result;
    }

    public Map<String, Object> changeStatus(FusionAccess.Identity who, String id, JsonNode body) {
        identifier(id, 192);
        String status = required(body, "status", 16).toUpperCase(Locale.ROOT);
        if (!TARGET_STATUSES.contains(status)) throw bad("状态只允许 ACTIVE/DISABLED/ARCHIVED");
        Map<String, Object> head = requireAsset(id);
        if ("ARCHIVED".equals(head.get("status"))) throw new ConflictException("已归档资产不可再变更状态");
        tx.executeWithoutResult(status0 -> {
            db.update("update midplat_capability_asset set status=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?", status, id);
            db.update("update midplat_capability set status=?,updated_at=CURRENT_TIMESTAMP where source_system='midplat' and source_type='capability-asset' and source_id=?", status, id);
        });
        Map<String, Object> result = new LinkedHashMap<>(detail(id));
        result.put("sync", "ACTIVE".equals(status) ? syncEnabledGrants(who, id, false) : disableAssetTargets(who, id));
        return result;
    }

    public Map<String, Object> retrySync(FusionAccess.Identity who, String id) {
        identifier(id, 192);
        Map<String, Object> head = requireAsset(id);
        List<Map<String, Object>> sync = "ACTIVE".equals(head.get("status")) ? syncEnabledGrants(who, id, false) : disableAssetTargets(who, id);
        Map<String, Object> result = new LinkedHashMap<>(detail(id));
        result.put("sync", sync);
        return result;
    }

    // ==================== 项目授权 ====================

    public Map<String, Object> grantsEnvelope(String project, String environment) {
        identifier(project, 64); identifier(environment, 32);
        if (db.queryForObject("select count(*) from midplat_platform where id=?", Long.class, project) == 0)
            throw new ResourceNotFoundException("项目平台不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project);
        out.put("environment", environment);
        out.put("policyRevision", currentPolicy(project, environment));
        out.put("grants", grantsOfProject(project, environment));
        return out;
    }

    public List<Map<String, Object>> grantsOfProject(String project, String environment) {
        identifier(project, 64); identifier(environment, 32);
        return db.queryForList("select g.id,g.asset_id,a.kind,a.name as asset_name,g.project_id,p.name as project_name,g.environment,g.operation," +
            "g.version_rule,g.revision_id,g.enabled,g.policy_revision,g.updated_at from midplat_capability_asset_grant g " +
            "join midplat_capability_asset a on a.id=g.asset_id join midplat_platform p on p.id=g.project_id " +
            "where g.project_id=? and g.environment=? order by a.name,g.operation", project, environment)
            .stream().map(this::grantView).toList();
    }

    /** 事务性替换项目在指定环境的全部资产授权；policyRevision 乐观锁，成功后镜像同步。 */
    public Map<String, Object> replaceGrants(FusionAccess.Identity who, String project, String environment, JsonNode body) {
        identifier(project, 64); identifier(environment, 32);
        if (!body.path("policyRevision").isIntegralNumber()) throw bad("缺少 policyRevision");
        long expectedPolicy = body.path("policyRevision").asLong();
        if (db.queryForObject("select count(*) from midplat_platform where id=?", Long.class, project) == 0)
            throw new ResourceNotFoundException("项目平台不存在");
        List<JsonNode> inputs = new ArrayList<>();
        if (body.path("grants").isArray()) body.path("grants").forEach(inputs::add);
        else throw bad("grants 必须为数组");
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode input : inputs) {
            String assetId = required(input, "assetId", 192); identifier(assetId, 192);
            String operation = required(input, "operation", 64); identifier(operation, 64);
            if (!seen.add(assetId + "|" + operation)) throw bad("同一资产的重复操作授权：" + operation);
            String rule = input.path("versionRule").asText("current").toLowerCase(Locale.ROOT);
            if (!Set.of("pinned", "current").contains(rule)) throw bad("版本规则只允许 pinned 或 current");
            var asset = db.queryForList("select name,status,current_revision_id from midplat_capability_asset where id=?", assetId);
            if (asset.isEmpty()) throw new ResourceNotFoundException("能力资产不存在：" + assetId);
            if ("ARCHIVED".equals(asset.get(0).get("status"))) throw bad("已归档资产不可授权：" + assetId);
            String revisionId = optional(input, "revisionId", 192);
            String resolvedRevisionId;
            if ("pinned".equals(rule)) {
                if (revisionId == null) throw bad("pinned 授权必须携带固定修订");
                if (db.queryForObject("select count(*) from midplat_capability_asset_revision where id=? and asset_id=?", Long.class, revisionId, assetId) == 0)
                    throw bad("固定修订不属于该能力资产");
                resolvedRevisionId = revisionId;
            } else {
                if (revisionId != null) throw bad("current 授权不应携带固定修订");
                resolvedRevisionId = (String) asset.get(0).get("current_revision_id");
                revisionId = null;
            }
            boolean enabled = !input.has("enabled") || input.path("enabled").asBoolean();
            Map<String, Object> grant = new LinkedHashMap<>();
            grant.put("assetId", assetId);
            grant.put("operation", operation);
            grant.put("versionRule", rule);
            grant.put("revisionId", revisionId);
            grant.put("resolvedRevisionId", resolvedRevisionId);
            grant.put("enabled", enabled);
            grant.put("assetStatus", asset.get(0).get("status"));
            normalized.add(grant);
        }
        if (currentPolicy(project, environment) != expectedPolicy) throw new ConflictException("项目授权策略已更新，请刷新后重试");
        List<Map<String, Object>> previous = db.queryForList("select asset_id,revision_id,project_id,environment,fusion_asset_id,fusion_revision_id from midplat_capability_asset_sync" +
            " where project_id=? and environment=? and enabled=true", project, environment);
        long nextPolicy = expectedPolicy + 1;
        tx.executeWithoutResult(status0 -> {
            int updated = db.update("update midplat_capability_asset_policy set policy_revision=?,updated_at=CURRENT_TIMESTAMP where project_id=? and environment=?",
                nextPolicy, project, environment);
            if (updated == 0) db.update("insert into midplat_capability_asset_policy(project_id,environment,policy_revision) values(?,?,?)",
                project, environment, nextPolicy);
            db.update("delete from midplat_capability_asset_grant where project_id=? and environment=?", project, environment);
            for (Map<String, Object> grant : normalized) {
                String id = "capg-" + FusionCatalog.hash(grant.get("assetId") + "|" + project + "|" + environment + "|" + grant.get("operation")).substring(0, 48);
                db.update("insert into midplat_capability_asset_grant(id,asset_id,project_id,environment,operation,version_rule,revision_id,enabled,policy_revision,created_at,updated_at)" +
                    " values(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, grant.get("assetId"), project, environment,
                    grant.get("operation"), grant.get("versionRule"), grant.get("revisionId"), grant.get("enabled"), nextPolicy);
            }
        });
        List<Map<String, Object>> sync = new ArrayList<>();
        Set<String> desired = new HashSet<>();
        Set<String> mirrored = new HashSet<>();
        for (Map<String, Object> grant : normalized) {
            boolean targetEnabled = Boolean.TRUE.equals(grant.get("enabled")) && "ACTIVE".equals(grant.get("assetStatus"));
            if (!targetEnabled) continue;
            String resolvedRevisionId = (String) grant.get("resolvedRevisionId");
            desired.add(grant.get("assetId") + "|" + resolvedRevisionId);
            if (!mirrored.add((String) grant.get("assetId") + "|" + resolvedRevisionId)) continue;
            var revision = db.queryForList("select a.id as asset_id,r.id,r.revision,r.content,a.kind,a.name,a.description" +
                " from midplat_capability_asset_revision r join midplat_capability_asset a on a.id=r.asset_id where r.id=?",
                resolvedRevisionId);
            if (!revision.isEmpty()) sync.add(syncTarget(who, revision.get(0), project, environment));
        }
        for (Map<String, Object> row : previous) {
            if (!desired.contains(row.get("asset_id") + "|" + row.get("revision_id"))) sync.add(disableTarget(who, row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project);
        result.put("environment", environment);
        result.put("policyRevision", nextPolicy);
        result.put("grants", grantsOfProject(project, environment));
        result.put("sync", sync);
        return result;
    }

    // ==================== 智能体侧镜像同步 ====================

    /** current 授权跟随最新修订；pinned 授权只在启用/重试时同步其固定修订，不随新修订漂移。 */
    private List<Map<String, Object>> syncEnabledGrants(FusionAccess.Identity who, String assetId, boolean currentOnly) {
        List<Map<String, Object>> sync = new ArrayList<>();
        Set<String> mirrored = new HashSet<>();
        var targets = db.queryForList("select distinct a.id as asset_id,a.kind,a.name,a.description,a.current_revision_id," +
            "g.project_id,g.environment,g.version_rule,g.revision_id as pinned_revision_id " +
            "from midplat_capability_asset_grant g join midplat_capability_asset a on a.id=g.asset_id " +
            "where g.asset_id=? and g.enabled=true and a.status='ACTIVE'" + (currentOnly ? " and g.version_rule='current'" : ""), assetId);
        for (Map<String, Object> target : targets) {
            String revisionId = "pinned".equals(target.get("version_rule"))
                ? (String) target.get("pinned_revision_id")
                : (String) target.get("current_revision_id");
            if (revisionId == null) continue;
            String key = target.get("project_id") + "|" + target.get("environment") + "|" + revisionId;
            if (!mirrored.add(key)) continue;
            var revision = db.queryForList("select a.id as asset_id,r.id,r.revision,r.content,a.kind,a.name,a.description" +
                " from midplat_capability_asset_revision r join midplat_capability_asset a on a.id=r.asset_id where r.id=?", revisionId);
            if (!revision.isEmpty()) sync.add(syncTarget(who, revision.get(0), (String) target.get("project_id"), (String) target.get("environment")));
        }
        return sync;
    }

    private List<Map<String, Object>> disableAssetTargets(FusionAccess.Identity who, String assetId) {
        List<Map<String, Object>> sync = new ArrayList<>();
        var rows = db.queryForList("select asset_id,revision_id,project_id,environment,fusion_asset_id,fusion_revision_id from midplat_capability_asset_sync" +
            " where asset_id=? and enabled=true", assetId);
        for (Map<String, Object> row : rows) sync.add(disableTarget(who, row));
        return sync;
    }

    /** 镜像单个目标：查询当前远端修订 → saveAsset(CAS) → assetEnabled(true)；失败记录 FAILED 供审查与重试。 */
    private Map<String, Object> syncTarget(FusionAccess.Identity who, Map<String, Object> assetRevision, String project, String environment) {
        String assetId = (String) assetRevision.get("asset_id");
        String revisionId = (String) assetRevision.get("id");
        String kind = (String) assetRevision.get("kind");
        String name = (String) assetRevision.get("name");
        String description = assetRevision.get("description") == null ? name : (String) assetRevision.get("description");
        String content = (String) assetRevision.get("content");
        String fusionAssetId = fusionAssetId(assetId, project, environment);
        String status; String error = null; String fusionRevisionId = null;
        try {
            JsonNode remote = agent.callAsMigrationService(who, project, environment, null, "assets", Map.of());
            long expected = 0;
            for (JsonNode node : iterable(remote)) if (fusionAssetId.equals(node.path("id").asText())) expected = node.path("revision").asLong(0);
            ObjectNode payload = json.createObjectNode();
            payload.put("id", fusionAssetId); payload.put("name", name); payload.put("kind", kind);
            payload.put("expectedRevision", expected); payload.set("content", fusionContent(kind, name, description, content));
            JsonNode saved = agent.callAsMigrationService(who, project, environment, null, "saveAsset", payload);
            fusionRevisionId = fusionRevisionId(saved, fusionAssetId);
            agent.callAsMigrationService(who, project, environment, null, "assetEnabled", Map.of("id", fusionAssetId, "enabled", true));
            if (fusionRevisionId == null || fusionRevisionId.isBlank()) throw new FusionUpstreamFault(502, "智能体未返回镜像资产修订标识");
            status = "SYNCED";
        } catch (RuntimeException failure) {
            status = "FAILED";
            error = truncate(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        recordSync(assetId, revisionId, project, environment, fusionAssetId, fusionRevisionId, status, true, error);
        return syncView(fusionAssetId, project, environment, status, fusionRevisionId, error);
    }

    private Map<String, Object> disableTarget(FusionAccess.Identity who, Map<String, Object> syncRow) {
        String assetId = (String) syncRow.get("asset_id");
        String revisionId = (String) syncRow.get("revision_id");
        String project = (String) syncRow.get("project_id");
        String environment = (String) syncRow.get("environment");
        String fusionAssetId = (String) syncRow.get("fusion_asset_id");
        String fusionRevisionId = syncRow.get("fusion_revision_id") == null ? null : String.valueOf(syncRow.get("fusion_revision_id"));
        String error = null;
        String status = "DISABLED";
        boolean enabled = false;
        if (fusionAssetId != null && !fusionAssetId.isBlank()) {
            try { agent.callAsMigrationService(who, project, environment, null, "assetEnabled", Map.of("id", fusionAssetId, "enabled", false)); }
            catch (RuntimeException failure) {
                status = "FAILED";
                enabled = true;
                error = truncate("停用镜像失败：" + failure.getMessage());
            }
        }
        recordSync(assetId, revisionId, project, environment, fusionAssetId, fusionRevisionId, status, enabled, error);
        return syncView(fusionAssetId, project, environment, status, fusionRevisionId, error);
    }

    private long currentPolicy(String project, String environment) {
        var rows = db.queryForList("select policy_revision from midplat_capability_asset_policy where project_id=? and environment=?", project, environment);
        if (rows.isEmpty() || rows.get(0).get("policy_revision") == null) return 0L;
        return ((Number) rows.get(0).get("policy_revision")).longValue();
    }

    /** Fusion saveAsset 返回单个对象；assets 列表返回数组。两种形状都要能取出 revisionId。 */
    static String fusionRevisionId(JsonNode saved, String fusionAssetId) {
        if (saved == null || saved.isNull() || saved.isMissingNode()) return null;
        if (saved.isArray()) {
            for (JsonNode node : saved) if (fusionAssetId.equals(node.path("id").asText())) {
                String id = node.path("revisionId").asText(null);
                return id == null || id.isBlank() ? null : id;
            }
            return null;
        }
        if (saved.isObject()) {
            if (saved.has("id") && !fusionAssetId.equals(saved.path("id").asText())) return null;
            String id = saved.path("revisionId").asText(null);
            return id == null || id.isBlank() ? null : id;
        }
        return null;
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return List.of();
        if (node.isArray()) return node;
        return List.of(node);
    }

    private void recordSync(String assetId, String revisionId, String project, String environment, String fusionAssetId, String fusionRevisionId, String status, boolean enabled, String error) {
        String id = "casy-" + FusionCatalog.hash(assetId + "|" + revisionId + "|" + project + "|" + environment).substring(0, 48);
        tx.executeWithoutResult(tx0 -> {
            var existing = db.queryForList("select fusion_revision_id from midplat_capability_asset_sync where id=?", id);
            if (!existing.isEmpty()) {
                // 失败尝试传 null 修订：保留此前已确认的镜像修订，避免把未知状态伪装成空。
                Object kept = fusionRevisionId != null ? fusionRevisionId : existing.get(0).get("fusion_revision_id");
                db.update("update midplat_capability_asset_sync set status=?,enabled=?,fusion_asset_id=?,fusion_revision_id=?,last_error=?," +
                    "last_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP where id=?", status, enabled, fusionAssetId, kept, error, id);
            } else {
                db.update("insert into midplat_capability_asset_sync(id,asset_id,revision_id,project_id,environment,fusion_asset_id,fusion_revision_id,status,enabled,last_error,last_attempt_at)" +
                    " values(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", id, assetId, revisionId, project, environment, fusionAssetId, fusionRevisionId, status, enabled, error);
            }
        });
    }

    /** 稳定镜像标识：SHA-256 前缀，<=64 且满足智能体侧 identifier 格式。 */
    static String fusionAssetId(String assetId, String project, String environment) {
        return "ca-" + FusionCatalog.hash(assetId + "|" + project + "|" + environment).substring(0, 40);
    }

    /** 智能体侧执行内核要求非技能资产带工具名与描述；这里只做表示层适配，不改变中台正文。 */
    private JsonNode fusionContent(String kind, String name, String description, String storedContent) {
        JsonNode stored = parse(storedContent);
        if (!Set.of("knowledge", "data").contains(kind)) return stored;
        ObjectNode adapted = stored.deepCopy();
        adapted.put("toolName", "knowledge".equals(kind) ? "search_knowledge" : "query_data");
        adapted.put("description", (description == null || description.isBlank() ? name : description));
        return adapted;
    }

    // ==================== 正文校验（对齐智能体侧 AssetService 规则） ====================

    private record Stored(String content, String hash) {}

    private Stored validateContent(String kind, JsonNode content) {
        if (content == null || !content.isObject()) throw bad("资产正文必须为 JSON 对象");
        if (content.toString().length() > MAX_CONTENT) throw bad("资产正文必须为对象且不超过 150000 字符");
        Set<String> allowed = "skill".equals(kind) ? SKILL_FIELDS : "tool".equals(kind) ? TOOL_FIELDS : DOCUMENT_FIELDS;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (Set.of("headers", "apiKey", "secret", "token").contains(field))
                throw bad("不要在资产正文保存密钥；受保护工具请通过已有业务服务接入（可使用 secretRef 部署配置）");
            if (!allowed.contains(field)) throw bad("资产正文包含不允许的字段：" + field);
        }
        if ("skill".equals(kind)) {
            required(content, "body", 100000);
        } else if ("tool".equals(kind)) {
            String function = required(content, "toolName", 64);
            if (!function.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) throw bad("工具名称格式无效");
            required(content, "description", 1000);
            safeUri(text(content, "url"));
            String method = text(content, "method");
            if (!Set.of("GET", "POST").contains(method)) throw bad("工具仅支持 GET/POST");
            JsonNode parameters = content.path("parameters");
            if (!parameters.isObject() || !"object".equals(parameters.path("type").asText())) throw bad("工具需要 object 参数模式");
            if (!content.path("readOnly").isBoolean()) throw bad("工具必须声明 readOnly");
            if ("POST".equals(method) && !content.path("readOnly").asBoolean()) throw bad("当前工具必须声明为只读，写操作不允许自动执行");
            if (content.has("secretRef") && (!content.path("secretRef").isTextual() || content.path("secretRef").asText().length() > 128))
                throw bad("secretRef 必须是长度不超过 128 的字符串引用");
        } else {
            JsonNode documents = content.path("documents");
            if (!documents.isArray() || documents.isEmpty() || documents.size() > 200) throw bad("请提供 1–200 条真实内容");
            for (JsonNode document : documents) {
                for (Iterator<String> it = document.fieldNames(); it.hasNext(); )
                    if (!DOCUMENT_ITEM_FIELDS.contains(it.next())) throw bad("文档条目包含不允许的字段");
                required(document, "title", 200);
                required(document, "text", 50000);
            }
        }
        String canonical = canonicalize(content);
        return new Stored(canonical, FusionCatalog.hash(canonical));
    }

    static URI safeUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) throw new IllegalArgumentException();
            for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())
                    throw new IllegalArgumentException();
            return uri;
        } catch (Exception invalid) {
            throw bad("工具地址不可用或属于禁止访问的本地/元数据地址");
        }
    }

    private String canonicalize(JsonNode node) {
        try { return json.writeValueAsString(canonical(node)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw bad("资产正文无法序列化"); }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            TreeSet<String> keys = new TreeSet<>(); node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) sorted.set(key, canonical(node.get(key)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode(); node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    // ==================== 内部工具 ====================

    private void insertRevision(String assetId, String revisionId, long revision, Stored stored, String note, String principal) {
        db.update("insert into midplat_capability_asset_revision(id,asset_id,revision,content,content_hash,note,created_by,created_at)" +
            " values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", revisionId, assetId, revision, stored.content(), stored.hash(), note, principal);
    }

    /** 目录头登记：cap-asset-<assetId>，正文不复制；latest_revision_id 记录资产最新修订标识。 */
    private void registerCatalogHead(String assetId, String kind, String name, String status, String revisionId) {
        int updated = db.update("update midplat_capability set name=?,status=?,latest_revision_id=?,updated_at=CURRENT_TIMESTAMP where source_system='midplat' and source_type='capability-asset' and source_id=?",
            name, status, revisionId, assetId);
        if (updated == 0) db.update("insert into midplat_capability(id,kind,source_system,source_type,source_id,name,status,latest_revision_id)" +
            " values(?,?,'midplat','capability-asset',?,?,?,?)", "cap-asset-" + assetId, kind, assetId, name, status, revisionId);
    }

    private Map<String, Object> requireAsset(String id) {
        var rows = db.queryForList("select id,kind,name,description,status,revision,current_revision_id,created_by,created_at,updated_at" +
            " from midplat_capability_asset where id=?", id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("能力资产不存在");
        return rows.get(0);
    }

    private Map<String, Object> summaryRow(Map<String, Object> row) {
        return assetView(row.get("id"), row.get("kind"), row.get("name"), row.get("description"), row.get("status"),
            number(row.get("revision")), row.get("content_hash"), row.get("current_revision_id"), row.get("created_at"), row.get("updated_at"),
            number(row.get("grant_count")), number(row.get("synced_count")), number(row.get("failed_count")), number(row.get("pending_count")));
    }

    private Map<String, Object> assetView(Object id, Object kind, Object name, Object description, Object status,
                                          Object revision, Object hash, Object currentRevisionId, Object createdAt, Object updatedAt,
                                          Object grantCount, Object synced, Object failed, Object pending) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id); view.put("kind", kind); view.put("name", name); view.put("description", description);
        view.put("status", status); view.put("revision", revision); view.put("hash", hash);
        view.put("currentRevisionId", currentRevisionId);
        view.put("createdAt", createdAt);
        view.put("updatedAt", updatedAt);
        view.put("grantCount", grantCount);
        view.put("sync", Map.of("synced", synced, "failed", failed, "pending", pending));
        return view;
    }

    private Map<String, Object> grantView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id")); view.put("assetId", row.get("asset_id")); view.put("assetKind", row.get("kind"));
        view.put("assetName", row.get("asset_name")); view.put("projectId", row.get("project_id")); view.put("projectName", row.get("project_name"));
        view.put("environment", row.get("environment")); view.put("operation", row.get("operation"));
        view.put("versionRule", row.get("version_rule")); view.put("revisionId", row.get("revision_id"));
        view.put("enabled", row.get("enabled")); view.put("policyRevision", number(row.get("policy_revision")));
        view.put("updatedAt", row.get("updated_at"));
        return view;
    }

    private Map<String, Object> syncView(Map<String, Object> row) {
        return syncView(row.get("fusion_asset_id"), row.get("project_id"), row.get("environment"), row.get("status"),
            row.get("fusion_revision_id"), row.get("last_error"));
    }

    private Map<String, Object> syncView(Object fusionAssetId, Object project, Object environment, Object status, Object fusionRevisionId, Object error) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("projectId", project); view.put("environment", environment);
        view.put("fusionAssetId", fusionAssetId); view.put("fusionRevisionId", fusionRevisionId);
        view.put("status", status); view.put("error", error);
        return view;
    }

    private Map<String, Object> pageEnvelope(List<Map<String, Object>> content, long total, long page, long pageSize) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content); out.put("page", page); out.put("pageSize", pageSize);
        out.put("total", total); out.put("totalPages", (total + pageSize - 1) / pageSize);
        return out;
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); } catch (Exception invalid) { throw new IllegalStateException("资产数据损坏", invalid); }
    }

    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }

    private static String text(JsonNode node, String key) { return node.path(key).asText(""); }

    private static String required(JsonNode node, String key, int max) {
        String value = text(node, key).trim();
        if (value.isEmpty() || value.length() > max) throw bad(key + " 不能为空或超过长度限制");
        return value;
    }

    private static String optional(JsonNode node, String key, int max) {
        if (!node.hasNonNull(key)) return null;
        String value = node.get(key).asText().trim();
        if (value.length() > max) throw bad(key + " 超过长度限制");
        return value.isEmpty() ? null : value;
    }

    static void identifier(String id, int max) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1," + max + "}")) throw bad("标识格式无效");
    }

    /** 422：与智能体侧同源的输入校验语义，走 FusionExceptionHandler。 */
    static FusionUpstreamFault bad(String message) { return new FusionUpstreamFault(422, message); }

    private static String truncate(String value) { return value == null ? null : value.length() > 500 ? value.substring(0, 500) : value; }
}
