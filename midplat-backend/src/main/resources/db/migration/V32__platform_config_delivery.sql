CREATE TABLE midplat_platform_delivery (
 id varchar(64) PRIMARY KEY,
 platform_id varchar(64) NOT NULL REFERENCES midplat_platform(id) ON DELETE CASCADE,
 revision bigint NOT NULL,
 endpoint text NOT NULL,
 payload_json text NOT NULL,
 credential_refs_json text NOT NULL,
 desired_hash varchar(64) NOT NULL,
 status varchar(24) NOT NULL,
 attempts integer NOT NULL DEFAULT 0,
 last_error varchar(500),
 confirmed_hash varchar(64),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_until timestamptz,
 finished_at timestamptz,
 UNIQUE(platform_id, revision)
);
CREATE INDEX midplat_delivery_pending ON midplat_platform_delivery(status,created_at);
