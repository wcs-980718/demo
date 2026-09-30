create table if not exists midplat_eval_case (
    id varchar(64) primary key,
    platform_id varchar(64),
    name varchar(128) not null,
    category varchar(64) not null,
    severity varchar(16) not null,
    source_type varchar(32) not null,
    source_ref varchar(256),
    input_text text not null,
    expected_json text not null,
    evaluator_type varchar(32) not null,
    review_status varchar(16) not null,
    content_hash varchar(64) not null,
    lifecycle_status varchar(16) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_eval_case_platform foreign key (platform_id)
        references midplat_platform (id) on delete set null,
    constraint chk_eval_case_severity check (severity in ('LOW','MEDIUM','HIGH','CRITICAL')),
    constraint chk_eval_case_source check (source_type in ('MANUAL','KNOWLEDGE','AGENT','INDICATOR','CLOSED_LOOP')),
    constraint chk_eval_case_evaluator check (evaluator_type in ('EXACT','CONTAINS_ALL','JSON_VALID','NUMERIC_RANGE','FORBIDDEN_TERMS','MANUAL')),
    constraint chk_eval_case_review check (review_status in ('DRAFT','REVIEWED','REJECTED')),
    constraint chk_eval_case_lifecycle check (lifecycle_status in ('ACTIVE','ARCHIVED'))
);

create unique index if not exists uq_eval_case_source_ref
    on midplat_eval_case (source_type, source_ref);
create index if not exists idx_eval_case_filters
    on midplat_eval_case (lifecycle_status, review_status, severity, category);
create index if not exists idx_eval_case_platform
    on midplat_eval_case (platform_id);
create index if not exists idx_eval_case_content_hash
    on midplat_eval_case (content_hash);

create table if not exists midplat_eval_audit_log (
    id varchar(64) primary key,
    action varchar(64) not null,
    resource_type varchar(64) not null,
    resource_id varchar(64) not null,
    source varchar(32) not null,
    actor_ref varchar(128) not null,
    summary_json text not null,
    occurred_at timestamp with time zone not null default current_timestamp
);

create index if not exists idx_eval_audit_resource
    on midplat_eval_audit_log (resource_type, resource_id, occurred_at);
