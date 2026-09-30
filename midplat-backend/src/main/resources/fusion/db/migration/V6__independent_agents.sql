-- W4 独立智能体与多分配：Agent 草稿、不可变 AgentVersion，部署按 bindingId 多实例。
-- 见《AI中台架构改造方案》04.3：同一智能体版本可分配多个项目；deployment 的 UNIQUE(project_id,environment) 解除。
CREATE TABLE fusion_agent(
 id varchar(64) PRIMARY KEY,
 workspace_id varchar(64) NOT NULL DEFAULT 'internal',
 name varchar(128) NOT NULL,
 description varchar(500) NOT NULL DEFAULT '',
 owner_subject varchar(128) NOT NULL DEFAULT '',
 status varchar(16) NOT NULL DEFAULT 'active',
 draft text NOT NULL,
 draft_revision bigint NOT NULL DEFAULT 0,
 latest_version_id varchar(64),
 source_project_id varchar(64),
 portable boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fusion_agent_version(
 id varchar(64) PRIMARY KEY,
 agent_id varchar(64) NOT NULL REFERENCES fusion_agent(id),
 sequence bigint NOT NULL,
 schema_version int NOT NULL DEFAULT 1,
 content text NOT NULL,
 content_hash varchar(64) NOT NULL,
 dependency_manifest text NOT NULL DEFAULT '{}',
 created_by varchar(128) NOT NULL DEFAULT '',
 note varchar(500) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(agent_id, sequence)
);

ALTER TABLE fusion_deployment ADD COLUMN binding_id varchar(64);
ALTER TABLE fusion_deployment ADD COLUMN source_agent_version_id varchar(64);
-- PostgreSQL 按默认命名删除；H2 的自动约束名不可预测，由 V7 Java 迁移动态删除。
ALTER TABLE fusion_deployment DROP CONSTRAINT IF EXISTS fusion_deployment_project_id_environment_key;
CREATE UNIQUE INDEX fusion_deployment_binding ON fusion_deployment(binding_id);

-- 回填：每个旧 deployment 生成一个项目私有 Agent（portable=false），当前草稿固化为版本 1。
-- determinstic id：ag-<deploymentId> / av-<deploymentId>-1，中台侧 binding 回填使用同一规则（V36）。
INSERT INTO fusion_agent(id, workspace_id, name, source_project_id, portable, draft, draft_revision)
SELECT 'ag-'||d.id, 'internal', d.name, d.project_id, false, d.draft, d.revision
FROM fusion_deployment d
WHERE NOT EXISTS(SELECT 1 FROM fusion_agent a WHERE a.id = 'ag-'||d.id);

-- 回填行的 content_hash 由应用侧按需补算（迁移跨 H2/PostgreSQL，无法在 SQL 内统一 SHA-256）。
INSERT INTO fusion_agent_version(id, agent_id, sequence, content, content_hash, created_by, note)
SELECT 'av-'||d.id||'-1', 'ag-'||d.id, 1, d.draft, '', 'migration', '回填自项目部署初始蓝图'
FROM fusion_deployment d
WHERE NOT EXISTS(SELECT 1 FROM fusion_agent_version v WHERE v.id = 'av-'||d.id||'-1');

UPDATE fusion_agent a SET latest_version_id = 'av-'||d.id||'-1'
FROM fusion_deployment d WHERE a.id = 'ag-'||d.id AND a.latest_version_id IS NULL;

UPDATE fusion_deployment SET binding_id = 'bnd-'||id, source_agent_version_id = 'av-'||id||'-1'
WHERE binding_id IS NULL;

-- AgentVersion 发布的幂等记录：同一 idempotencyKey 重复请求返回原版本，不同内容返回 409。
CREATE TABLE fusion_agent_publication(
 id varchar(64) PRIMARY KEY,
 agent_id varchar(64) NOT NULL REFERENCES fusion_agent(id),
 idempotency_key varchar(64) NOT NULL,
 request_hash varchar(64) NOT NULL,
 version_id varchar(64),
 status varchar(16) NOT NULL,
 error varchar(500),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(agent_id, idempotency_key)
);

CREATE TABLE fusion_agent_audit(
 id varchar(64) PRIMARY KEY,
 agent_id varchar(64) NOT NULL REFERENCES fusion_agent(id),
 principal varchar(128) NOT NULL,
 action varchar(64) NOT NULL,
 target varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
