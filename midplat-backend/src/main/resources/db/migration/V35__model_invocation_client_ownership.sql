ALTER TABLE midplat_model_invocation ADD COLUMN client_id varchar(64);
ALTER TABLE midplat_model_invocation ADD COLUMN credential_id varchar(64);
CREATE INDEX midplat_invocation_client ON midplat_model_invocation(client_id,created_at);