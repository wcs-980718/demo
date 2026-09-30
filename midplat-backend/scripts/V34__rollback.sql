-- W1 V34 的回滚脚本（仅在隔离环境演练；见《AI中台架构改造方案》12.7 回退边界）。
-- 前置条件：
--   1. V34 之后的迁移（V35+，若已存在）必须先回滚；
--   2. midplat_access_migration 中已有回填记录时，需先确认无生产凭证依赖（W6 兼容期未结束不得执行）；
--   3. 已向真实 Client 发放过新凭证后，禁止回滚（只能前滚修复），因为摘要是不可逆的。
-- 执行方式（隔离 PostgreSQL）：
--   PGPASSWORD=... psql -h <host> -p <port> -U <user> -d ai_midplat -f V34__rollback.sql
-- 执行后必须同步修复 flyway_schema_history（删除 version=34 的行），否则后续启动校验失败。
BEGIN;

DROP INDEX IF EXISTS midplat_platform_legacy_digest;
DROP INDEX IF EXISTS midplat_credential_client;

DROP TABLE IF EXISTS midplat_access_migration;
DROP TABLE IF EXISTS midplat_credential_grant;
DROP TABLE IF EXISTS midplat_client_entitlement;
DROP TABLE IF EXISTS midplat_credential;
DROP TABLE IF EXISTS midplat_client;
DROP TABLE IF EXISTS midplat_consumer;

ALTER TABLE midplat_platform DROP COLUMN IF EXISTS legacy_token_digest;

DELETE FROM flyway_schema_history WHERE version = '34';

COMMIT;
