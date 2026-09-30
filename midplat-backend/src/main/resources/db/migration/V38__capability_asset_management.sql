-- 能力资产管理闭环：skill/tool/knowledge/data 四类资产的真实主数据、不可变修订、
-- 项目授权与智能体侧镜像同步状态。midplat_capability 目录头继续以 cap-asset-<assetId>
-- 登记四类资产，不复制模型/提示词正文。表与列风格与 V36 保持一致，可在 H2(PostgreSQL 模式)
-- 与 PostgreSQL 上执行。
CREATE TABLE midplat_capability_asset (
    id varchar(64) PRIMARY KEY,
    kind varchar(16) NOT NULL,
    name varchar(128) NOT NULL,
    description varchar(500),
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    current_revision_id varchar(64),
    revision bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    owner_workspace_id varchar(64) NOT NULL DEFAULT 'internal',
    created_by varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_capability_asset_kind CHECK (kind IN ('skill','tool','knowledge','data')),
    CONSTRAINT ck_capability_asset_status CHECK (status IN ('DRAFT','ACTIVE','DISABLED','ARCHIVED'))
);
CREATE INDEX midplat_capability_asset_kind_status ON midplat_capability_asset(kind, status);

CREATE TABLE midplat_capability_asset_revision (
    id varchar(64) PRIMARY KEY,
    asset_id varchar(64) NOT NULL REFERENCES midplat_capability_asset(id),
    revision bigint NOT NULL,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    note varchar(500),
    created_by varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(asset_id, revision)
);

CREATE TABLE midplat_capability_asset_grant (
    id varchar(64) PRIMARY KEY,
    asset_id varchar(64) NOT NULL REFERENCES midplat_capability_asset(id),
    project_id varchar(64) NOT NULL REFERENCES midplat_platform(id),
    environment varchar(32) NOT NULL,
    operation varchar(64) NOT NULL,
    version_rule varchar(16) NOT NULL DEFAULT 'current',
    revision_id varchar(64) REFERENCES midplat_capability_asset_revision(id),
    enabled boolean NOT NULL DEFAULT true,
    policy_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(asset_id, project_id, environment, operation),
    CONSTRAINT ck_capability_asset_grant_rule CHECK (version_rule IN ('pinned','current'))
);
CREATE INDEX midplat_capability_asset_grant_project ON midplat_capability_asset_grant(project_id, environment);

-- 项目环境授权策略版本：空授权列表也必须递增，不能回退到 0。
CREATE TABLE midplat_capability_asset_policy (
    project_id varchar(64) NOT NULL REFERENCES midplat_platform(id),
    environment varchar(32) NOT NULL,
    policy_revision bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, environment)
);

-- 智能体侧镜像同步状态：目标唯一 (asset, revision, project, environment)，
-- 供项目环境维度的状态检索建组合索引；失败保留原因并允许重试。
CREATE TABLE midplat_capability_asset_sync (
    id varchar(64) PRIMARY KEY,
    asset_id varchar(64) NOT NULL REFERENCES midplat_capability_asset(id),
    revision_id varchar(64) NOT NULL REFERENCES midplat_capability_asset_revision(id),
    project_id varchar(64) NOT NULL REFERENCES midplat_platform(id),
    environment varchar(32) NOT NULL,
    fusion_asset_id varchar(64),
    fusion_revision_id varchar(64),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    enabled boolean NOT NULL DEFAULT true,
    last_error varchar(500),
    last_attempt_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(asset_id, revision_id, project_id, environment),
    CONSTRAINT ck_capability_asset_sync_status CHECK (status IN ('PENDING','SYNCED','FAILED','DISABLED'))
);
CREATE INDEX midplat_capability_asset_sync_target ON midplat_capability_asset_sync(project_id, environment, status);
