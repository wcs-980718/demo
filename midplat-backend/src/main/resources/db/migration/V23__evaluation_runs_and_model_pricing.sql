alter table midplat_model
    add column if not exists input_price_per_million numeric(20, 8) not null default 0;

alter table midplat_model
    add column if not exists output_price_per_million numeric(20, 8) not null default 0;

alter table midplat_model
    add constraint chk_midplat_model_input_price_nonnegative check (input_price_per_million >= 0);

alter table midplat_model
    add constraint chk_midplat_model_output_price_nonnegative check (output_price_per_million >= 0);

create table if not exists midplat_eval_run (
    id varchar(64) primary key,
    dataset_version_id varchar(64) not null,
    platform_id varchar(64) not null,
    model_id varchar(64) not null,
    prompt_id varchar(64) not null,
    target_snapshot_json text not null,
    parameters_json text not null,
    status varchar(16) not null,
    cancel_requested boolean not null default false,
    worker_owner varchar(64),
    heartbeat_at timestamp with time zone,
    lease_expires_at timestamp with time zone,
    retry_revision integer not null default 0,
    source_run_id varchar(64),
    total_count integer not null default 0,
    completed_count integer not null default 0,
    passed_count integer not null default 0,
    failed_count integer not null default 0,
    error_count integer not null default 0,
    manual_review_count integer not null default 0,
    prompt_tokens bigint not null default 0,
    completion_tokens bigint not null default 0,
    total_tokens bigint not null default 0,
    total_cost numeric(20, 8) not null default 0,
    avg_latency_ms bigint not null default 0,
    p95_latency_ms bigint not null default 0,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    error_code varchar(64),
    error_summary varchar(512),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_eval_run_status check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    constraint chk_eval_run_nonnegative check (
        retry_revision >= 0 and total_count >= 0 and completed_count >= 0 and passed_count >= 0 and failed_count >= 0
        and error_count >= 0 and manual_review_count >= 0
        and prompt_tokens >= 0 and completion_tokens >= 0 and total_tokens >= 0 and total_cost >= 0
        and avg_latency_ms >= 0 and p95_latency_ms >= 0
    ),
    constraint chk_eval_run_counts check (
        completed_count <= total_count
        and passed_count + failed_count + error_count + manual_review_count <= completed_count
    ),
    constraint chk_eval_run_terminal_completion check (
        (status in ('QUEUED', 'RUNNING') and completed_at is null)
        or (status in ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED') and completed_at is not null)
    ),
    constraint chk_eval_run_lease check (
        (status = 'RUNNING' and worker_owner is not null and heartbeat_at is not null and lease_expires_at is not null)
        or (status <> 'RUNNING' and worker_owner is null and heartbeat_at is null and lease_expires_at is null)
    ),
    constraint fk_eval_run_dataset_version foreign key (dataset_version_id)
        references midplat_eval_dataset_version (id),
    constraint fk_eval_run_platform foreign key (platform_id)
        references midplat_platform (id),
    constraint fk_eval_run_model foreign key (model_id)
        references midplat_model (id),
    constraint fk_eval_run_prompt foreign key (prompt_id)
        references midplat_prompt (id),
    constraint fk_eval_run_source foreign key (source_run_id)
        references midplat_eval_run (id)
);

create index if not exists idx_eval_run_dataset_version on midplat_eval_run (dataset_version_id, created_at desc);
create index if not exists idx_eval_run_platform on midplat_eval_run (platform_id, created_at desc);
create index if not exists idx_eval_run_model on midplat_eval_run (model_id, created_at desc);
create index if not exists idx_eval_run_prompt on midplat_eval_run (prompt_id, created_at desc);
create index if not exists idx_eval_run_status on midplat_eval_run (status, created_at asc);
create index if not exists idx_eval_run_recovery_lease on midplat_eval_run (status, lease_expires_at asc);
create index if not exists idx_eval_run_source on midplat_eval_run (source_run_id, created_at desc);

create table if not exists midplat_eval_result (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    case_id varchar(64) not null,
    order_no integer not null,
    snapshot_name varchar(128) not null,
    snapshot_category varchar(64) not null,
    snapshot_severity varchar(16) not null,
    snapshot_input_text text not null,
    snapshot_expected_json text not null,
    snapshot_evaluator_type varchar(32) not null,
    snapshot_content_hash varchar(64) not null,
    actual_output text,
    score numeric(10, 4),
    passed boolean,
    review_required boolean not null default false,
    status varchar(16) not null,
    error_code varchar(64),
    error_summary varchar(512),
    trace_summary_json text,
    latency_ms bigint not null default 0,
    prompt_tokens bigint not null default 0,
    completion_tokens bigint not null default 0,
    total_tokens bigint not null default 0,
    cost numeric(20, 8) not null default 0,
    attempt_count integer not null default 0,
    reviewed_at timestamp with time zone,
    review_summary varchar(512),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_eval_result_run foreign key (run_id) references midplat_eval_run (id),
    constraint uq_eval_result_run_case unique (run_id, case_id),
    constraint uq_eval_result_run_order unique (run_id, order_no),
    constraint chk_eval_result_status check (status in ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'ERROR', 'CANCELLED')),
    constraint chk_eval_result_nonnegative check (
        order_no >= 1 and latency_ms >= 0 and prompt_tokens >= 0 and completion_tokens >= 0
        and total_tokens >= 0 and cost >= 0 and attempt_count >= 0
    ),
    constraint chk_eval_result_review check (
        (not review_required or (status = 'PENDING' and reviewed_at is null and review_summary is null))
        and (reviewed_at is null or (not review_required and status in ('PASSED', 'FAILED') and review_summary is not null))
        and (review_summary is null or reviewed_at is not null)
    )
);

create index if not exists idx_eval_result_run_order on midplat_eval_result (run_id, order_no asc);
