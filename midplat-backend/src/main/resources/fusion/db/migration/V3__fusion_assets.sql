CREATE TABLE fusion_asset (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, environment varchar(32) NOT NULL,
 kind varchar(16) NOT NULL, name varchar(128) NOT NULL, enabled boolean NOT NULL DEFAULT true,
 revision bigint NOT NULL, current_revision_id varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE fusion_asset_revision (
 id varchar(64) PRIMARY KEY, asset_id varchar(64) NOT NULL REFERENCES fusion_asset(id), revision bigint NOT NULL,
 content text NOT NULL, content_hash varchar(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(asset_id,revision)
);
CREATE TABLE fusion_tool_call (
 run_id varchar(64) NOT NULL REFERENCES fusion_run(id), call_id varchar(128) NOT NULL,
 asset_revision_id varchar(64) NOT NULL REFERENCES fusion_asset_revision(id), request_hash varchar(64) NOT NULL,
 status varchar(24) NOT NULL, result text, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(run_id,call_id)
);
