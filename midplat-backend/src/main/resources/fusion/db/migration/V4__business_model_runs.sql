ALTER TABLE fusion_run ADD COLUMN origin varchar(24) NOT NULL DEFAULT 'agenthub';
CREATE INDEX fusion_run_business_project ON fusion_run(deployment_id,origin,created_at);
