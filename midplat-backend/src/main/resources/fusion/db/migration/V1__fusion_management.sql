CREATE TABLE fusion_definition(id varchar(64) PRIMARY KEY,name varchar(128) NOT NULL,content text NOT NULL);
CREATE TABLE fusion_deployment(
 id varchar(64) PRIMARY KEY,project_id varchar(64) NOT NULL,environment varchar(32) NOT NULL,
 definition_id varchar(64) NOT NULL REFERENCES fusion_definition(id),name varchar(128) NOT NULL,
 revision bigint NOT NULL DEFAULT 0,draft text NOT NULL,published_release_id varchar(64),active_release_id varchar(64),pending_job_id varchar(64),
 UNIQUE(project_id,environment)
);
CREATE TABLE fusion_publication(
 id varchar(64) PRIMARY KEY,deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),idempotency_key varchar(64) NOT NULL,
 request_hash varchar(64) NOT NULL,principal varchar(128) NOT NULL,status varchar(16) NOT NULL,draft text NOT NULL,
 note varchar(500) NOT NULL,restored_from varchar(64),release_id varchar(64),error varchar(500),lease_token varchar(64),lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(deployment_id,idempotency_key)
);
CREATE INDEX fusion_publication_pending ON fusion_publication(status,lease_until);
CREATE TABLE fusion_release(
 id varchar(64) PRIMARY KEY,deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),sequence bigint NOT NULL,
 publication_id varchar(64) NOT NULL UNIQUE REFERENCES fusion_publication(id),note varchar(500) NOT NULL,restored_from varchar(64),
 content_hash varchar(64) NOT NULL,snapshot text NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,sequence), UNIQUE(deployment_id,id)
);
ALTER TABLE fusion_deployment ADD CONSTRAINT fusion_published_owner FOREIGN KEY(id,published_release_id) REFERENCES fusion_release(deployment_id,id);
ALTER TABLE fusion_deployment ADD CONSTRAINT fusion_active_owner FOREIGN KEY(id,active_release_id) REFERENCES fusion_release(deployment_id,id);
CREATE TABLE fusion_audit(id varchar(64) PRIMARY KEY,deployment_id varchar(64) NOT NULL REFERENCES fusion_deployment(id),
 principal varchar(128) NOT NULL,action varchar(64) NOT NULL,target varchar(128) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE fusion_outbox(id varchar(64) PRIMARY KEY,aggregate_id varchar(64) NOT NULL,event_type varchar(64) NOT NULL,payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,delivered_at timestamptz);
