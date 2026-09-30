-- W4 多分配与 W3 能力目录：项目分配 Binding、默认路由、统一能力目录头与授权。
-- 见《AI中台架构改造方案》04.2/04.3。回填 id 规则与 fusion V6 保持一致：
-- binding = bnd-<deploymentId>，agent = ag-<deploymentId>，agent version = av-<deploymentId>-1。
CREATE TABLE midplat_project_agent_binding (
    id varchar(64) PRIMARY KEY,
    project_id varchar(64) NOT NULL REFERENCES midplat_platform(id),
    environment varchar(32) NOT NULL,
    alias varchar(64) NOT NULL,
    agent_id varchar(64) NOT NULL,
    desired_agent_version_id varchar(64),
    deployment_id varchar(64) NOT NULL,
    source_definition_id varchar(64),
    source_name varchar(128),
    parameters text NOT NULL DEFAULT '{}',
    resource_bindings text NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL,
    generation bigint NOT NULL DEFAULT 1,
    operation_id varchar(64),
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(project_id, environment, alias)
);
CREATE UNIQUE INDEX midplat_project_agent_binding_deployment ON midplat_project_agent_binding(deployment_id);
CREATE INDEX midplat_project_agent_binding_project ON midplat_project_agent_binding(project_id, environment);

-- 每种旧接口协议最多一个显式默认分配；route_kind：legacy-agent-runs / legacy-runtime-chat / legacy-business-chat。
CREATE TABLE midplat_project_runtime_default (
    project_id varchar(64) NOT NULL,
    environment varchar(32) NOT NULL,
    route_kind varchar(64) NOT NULL,
    binding_id varchar(64) NOT NULL REFERENCES midplat_project_agent_binding(id),
    row_version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, environment, route_kind)
);

-- 统一能力目录头：模型/提示词正文仍由各自主表与 catalog revision 承载，这里登记目录元数据。
CREATE TABLE midplat_capability (
    id varchar(64) PRIMARY KEY,
    kind varchar(32) NOT NULL,
    source_system varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id varchar(128) NOT NULL,
    name varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    latest_revision_id varchar(64),
    owner_workspace_id varchar(64) DEFAULT 'internal',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(source_system, source_type, source_id)
);
CREATE INDEX midplat_capability_kind ON midplat_capability(kind, status);

CREATE TABLE midplat_capability_grant (
    id varchar(192) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    environment varchar(32) NOT NULL,
    capability_id varchar(64) NOT NULL REFERENCES midplat_capability(id),
    operation varchar(64) NOT NULL DEFAULT 'use',
    version_rule varchar(32) NOT NULL DEFAULT 'pinned',
    enabled boolean NOT NULL DEFAULT true,
    policy_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, environment, capability_id, operation)
);

-- 回填：能力目录头（模型 + 提示词），latest revision 取该资源最新一条 catalog revision。
INSERT INTO midplat_capability(id, kind, source_system, source_type, source_id, name, status)
SELECT 'cap-model-'||m.id, 'model', 'midplat', 'model', m.id, m.name, 'ACTIVE'
FROM midplat_model m
WHERE NOT EXISTS(SELECT 1 FROM midplat_capability c WHERE c.source_system='midplat' AND c.source_type='model' AND c.source_id=m.id);

INSERT INTO midplat_capability(id, kind, source_system, source_type, source_id, name, status)
SELECT 'cap-prompt-'||p.id, 'prompt', 'midplat', 'prompt', p.id, p.name, 'ACTIVE'
FROM midplat_prompt p
WHERE NOT EXISTS(SELECT 1 FROM midplat_capability c WHERE c.source_system='midplat' AND c.source_type='prompt' AND c.source_id=p.id);

UPDATE midplat_capability c SET latest_revision_id = (
    SELECT r.id FROM midplat_catalog_revision r
    WHERE r.resource_type = c.source_type AND r.resource_id = c.source_id
    ORDER BY r.created_at DESC LIMIT 1
)
WHERE c.latest_revision_id IS NULL;

-- 回填：模型授权 → 统一能力授权（原 midplat_model_grant 保持不动，双读过渡）。
INSERT INTO midplat_capability_grant(id, project_id, environment, capability_id, enabled)
SELECT 'capg-'||g.model_id||'-'||g.project_id||'-'||g.environment, g.project_id, g.environment,
       'cap-model-'||g.model_id, g.enabled
FROM midplat_model_grant g
WHERE EXISTS(SELECT 1 FROM midplat_capability c WHERE c.id='cap-model-'||g.model_id)
ON CONFLICT DO NOTHING;

-- 回填：旧单绑定 → 新分配表；原唯一部署成为旧协议默认分配。
INSERT INTO midplat_project_agent_binding(id, project_id, environment, alias, agent_id, desired_agent_version_id, deployment_id, source_definition_id, source_name, status, generation)
SELECT 'bnd-'||b.deployment_id, b.project_id, b.environment, 'default',
       'ag-'||b.deployment_id, 'av-'||b.deployment_id||'-1', b.deployment_id, b.definition_id, b.name,
       CASE WHEN b.status = 'ready' THEN 'READY' ELSE 'PENDING' END, 1
FROM midplat_fusion_binding b
WHERE NOT EXISTS(SELECT 1 FROM midplat_project_agent_binding n WHERE n.id = 'bnd-'||b.deployment_id);

INSERT INTO midplat_project_runtime_default(project_id, environment, route_kind, binding_id)
SELECT b.project_id, b.environment, 'legacy-agent-runs', 'bnd-'||b.deployment_id
FROM midplat_fusion_binding b
WHERE b.status = 'ready'
ON CONFLICT DO NOTHING;
