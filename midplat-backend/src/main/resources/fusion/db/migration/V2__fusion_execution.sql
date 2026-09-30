ALTER TABLE fusion_deployment ADD COLUMN activation_revision bigint NOT NULL DEFAULT 0;
CREATE TABLE fusion_runtime_unit (
 release_id varchar(64) PRIMARY KEY REFERENCES fusion_release(id), deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 content_hash varchar(64) NOT NULL, status varchar(24) NOT NULL, error varchar(500),
 prepared_at timestamptz, runtime_version varchar(64) NOT NULL
);
CREATE TABLE fusion_activation (
 id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 release_id varchar(64) NOT NULL REFERENCES fusion_release(id), previous_release_id varchar(64),
 revision bigint NOT NULL, principal varchar(128) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,revision)
);
CREATE TABLE fusion_session (
 id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 release_id varchar(64) NOT NULL REFERENCES fusion_release(id), principal varchar(128) NOT NULL, task_key varchar(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'open', created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at timestamptz NOT NULL
);
CREATE TABLE fusion_run (
 id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 release_id varchar(64) NOT NULL REFERENCES fusion_release(id), session_id varchar(64) NOT NULL REFERENCES fusion_session(id),
 principal varchar(128) NOT NULL, task_key varchar(64) NOT NULL, input text NOT NULL, output text NOT NULL DEFAULT '',
 status varchar(24) NOT NULL, error varchar(500), request_key varchar(64) NOT NULL, request_hash varchar(64) NOT NULL,
 lease_owner varchar(64), lease_until timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 started_at timestamptz, finished_at timestamptz, last_event bigint NOT NULL DEFAULT 0,
 UNIQUE(deployment_id,principal,request_key)
);
CREATE INDEX fusion_run_pending ON fusion_run(status,created_at);
CREATE INDEX fusion_run_session ON fusion_run(session_id,created_at);
CREATE TABLE fusion_run_event (
 run_id varchar(64) NOT NULL REFERENCES fusion_run(id), sequence bigint NOT NULL, event_type varchar(32) NOT NULL,
 payload text NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(run_id,sequence)
);
