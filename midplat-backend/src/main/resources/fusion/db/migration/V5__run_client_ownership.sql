-- W2: 运行/会话按接入应用 Client 隔离。
-- 1) session 加 client_id；2) 重建 fusion_run 把 client_id 纳入幂等唯一键，
-- 换掉 DB 自动命名的 UNIQUE(deployment_id,principal,request_key)（H2 自动命名不可预测，改用表重建，
-- 同时符合 PostgreSQL 与 H2 的语义）。旧记录迁移时 client_id 置空，保留既有管理/兼容行为。
ALTER TABLE fusion_session ADD COLUMN client_id varchar(64);
CREATE INDEX fusion_session_client_idx ON fusion_session(id,client_id);

ALTER TABLE fusion_run RENAME TO fusion_run_legacy;
CREATE TABLE fusion_run (
 id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 release_id varchar(64) NOT NULL REFERENCES fusion_release(id), session_id varchar(64) NOT NULL REFERENCES fusion_session(id),
 principal varchar(128) NOT NULL, task_key varchar(64) NOT NULL, input text NOT NULL, output text NOT NULL DEFAULT '',
 status varchar(24) NOT NULL, error varchar(500), request_key varchar(64) NOT NULL, request_hash varchar(64) NOT NULL,
 origin varchar(24) NOT NULL DEFAULT 'agenthub', client_id varchar(64),
 lease_owner varchar(64), lease_until timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 started_at timestamptz, finished_at timestamptz, last_event bigint NOT NULL DEFAULT 0,
 UNIQUE(deployment_id,principal,request_key,client_id)
);
INSERT INTO fusion_run(id,deployment_id,release_id,session_id,principal,task_key,input,output,status,error,request_key,request_hash,origin,client_id,lease_owner,lease_until,created_at,started_at,finished_at,last_event)
SELECT id,deployment_id,release_id,session_id,principal,task_key,input,output,status,error,request_key,request_hash,origin,NULL,lease_owner,lease_until,created_at,started_at,finished_at,last_event FROM fusion_run_legacy;
DROP TABLE fusion_run_legacy CASCADE;
CREATE INDEX fusion_run_pending ON fusion_run(status,created_at);
CREATE INDEX fusion_run_session ON fusion_run(session_id,created_at);
CREATE INDEX fusion_run_business_project ON fusion_run(deployment_id,origin,created_at);
CREATE INDEX fusion_run_client_idx ON fusion_run(deployment_id,client_id,created_at);
ALTER TABLE fusion_run_event ADD CONSTRAINT fusion_run_event_run_fk FOREIGN KEY (run_id) REFERENCES fusion_run(id);