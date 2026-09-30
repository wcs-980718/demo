CREATE TABLE midplat_model_invocation (
 id varchar(64) PRIMARY KEY, run_id varchar(64) NOT NULL, node_id varchar(64) NOT NULL,
 call_sequence integer NOT NULL, project_id varchar(64) NOT NULL, environment varchar(32) NOT NULL,
 deployment_id varchar(64) NOT NULL, release_id varchar(64) NOT NULL, principal varchar(128) NOT NULL,
 model_revision_id varchar(64) NOT NULL, status varchar(24) NOT NULL,
 input_tokens bigint, output_tokens bigint, usage_status varchar(16) NOT NULL DEFAULT 'unknown',
 error varchar(500), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at timestamptz,
 UNIQUE(run_id,node_id,call_sequence)
);
CREATE INDEX midplat_invocation_project ON midplat_model_invocation(project_id,created_at);
CREATE TABLE midplat_cancelled_run (run_id varchar(64) PRIMARY KEY, expires_at timestamptz NOT NULL);
CREATE TABLE midplat_model_availability (
 model_id varchar(64) PRIMARY KEY REFERENCES midplat_model(id), enabled boolean NOT NULL DEFAULT true
);
