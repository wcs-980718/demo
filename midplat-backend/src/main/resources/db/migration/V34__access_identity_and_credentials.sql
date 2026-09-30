-- W1 身份与凭证基础：客户 Consumer / 接入应用 Client / 凭证 Credential / 应用上限 Entitlement / 凭证授权 Grant。
-- 见《AI中台架构改造方案》04.2：secret 原文不落库，只存受 pepper 保护的 HMAC-SHA-256 摘要。
CREATE TABLE midplat_consumer (
    id varchar(64) PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(128) NOT NULL,
    type varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    owner_workspace_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE midplat_client (
    id varchar(64) PRIMARY KEY,
    consumer_id varchar(64) NOT NULL REFERENCES midplat_consumer(id),
    project_id varchar(64) NOT NULL REFERENCES midplat_platform(id),
    environment varchar(32) NOT NULL,
    name varchar(128) NOT NULL,
    code varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    policy_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(consumer_id, code)
);

CREATE TABLE midplat_credential (
    id varchar(64) PRIMARY KEY,
    client_id varchar(64) NOT NULL REFERENCES midplat_client(id),
    key_id varchar(64) NOT NULL UNIQUE,
    secret_digest varchar(128) NOT NULL,
    pepper_version int NOT NULL,
    label varchar(128) NOT NULL,
    status varchar(16) NOT NULL,
    valid_from timestamptz NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    last_used_at timestamptz,
    predecessor_id varchar(64) REFERENCES midplat_credential(id),
    secret_revealed boolean NOT NULL DEFAULT false,
    policy_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX midplat_credential_client ON midplat_credential(client_id);

CREATE TABLE midplat_client_entitlement (
    id varchar(64) PRIMARY KEY,
    client_id varchar(64) NOT NULL REFERENCES midplat_client(id),
    resource_kind varchar(32) NOT NULL,
    resource_id varchar(128) NOT NULL,
    action varchar(64) NOT NULL,
    constraints text,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(client_id, resource_kind, resource_id, action)
);

CREATE TABLE midplat_credential_grant (
    id varchar(64) PRIMARY KEY,
    credential_id varchar(64) NOT NULL REFERENCES midplat_credential(id),
    entitlement_id varchar(64) NOT NULL REFERENCES midplat_client_entitlement(id),
    constraints text,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(credential_id, entitlement_id)
);

-- 旧项目 token 的受保护摘要索引：legacy key 没有 keyId，校验时对整个 token 计算 HMAC 后定位。
ALTER TABLE midplat_platform ADD COLUMN legacy_token_digest varchar(128);
CREATE INDEX midplat_platform_legacy_digest ON midplat_platform(legacy_token_digest);

-- 迁移注册表：记录旧 token → 新凭证的回填映射（回填由 W6 批量迁移命令执行，此处先建结构）。
CREATE TABLE midplat_access_migration (
    id varchar(64) PRIMARY KEY,
    source_type varchar(32) NOT NULL,
    source_id varchar(64) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id varchar(64) NOT NULL,
    source_hash varchar(64) NOT NULL,
    migration_batch varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(source_type, source_id, migration_batch)
);
