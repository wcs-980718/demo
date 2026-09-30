CREATE TABLE midplat_catalog_revision (
 id varchar(64) PRIMARY KEY, resource_type varchar(16) NOT NULL, resource_id varchar(64) NOT NULL,
 content_hash varchar(64) NOT NULL, content text NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(resource_type,resource_id,content_hash)
);
CREATE INDEX midplat_catalog_owner ON midplat_catalog_revision(resource_type,resource_id);
CREATE TABLE midplat_fusion_binding (
 project_id varchar(64) NOT NULL REFERENCES midplat_platform(id), environment varchar(32) NOT NULL,
 deployment_id varchar(64) NOT NULL, operation_id varchar(64) NOT NULL UNIQUE,
 definition_id varchar(64) NOT NULL, name varchar(128) NOT NULL, status varchar(16) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(project_id,environment)
);
CREATE TABLE midplat_model_grant (
 project_id varchar(64) NOT NULL REFERENCES midplat_platform(id), environment varchar(32) NOT NULL,
 model_id varchar(64) NOT NULL REFERENCES midplat_model(id), enabled boolean NOT NULL,
 PRIMARY KEY(project_id,environment,model_id)
);
CREATE TABLE midplat_catalog_reference (
 revision_id varchar(64) NOT NULL REFERENCES midplat_catalog_revision(id),
 deployment_id varchar(64) NOT NULL, model_id varchar(64) REFERENCES midplat_model(id),
 prompt_id varchar(64) REFERENCES midplat_prompt(id), PRIMARY KEY(revision_id,deployment_id)
);
