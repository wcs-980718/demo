create table if not exists midplat_eval_gate_policy (
    id varchar(64) primary key,
    name varchar(128) not null,
    platform_id varchar(64),
    category varchar(64),
    min_pass_rate numeric(5, 2) not null,
    max_cost_growth_percent numeric(20, 8) not null,
    max_avg_latency_ms bigint not null,
    max_p95_latency_ms bigint not null,
    require_critical_cases_passed boolean not null,
    enabled boolean not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint uq_eval_gate_policy_name unique (name),
    constraint chk_eval_gate_policy_ranges check (
        min_pass_rate >= 0 and min_pass_rate <= 100
        and max_cost_growth_percent >= 0 and max_cost_growth_percent <= 999999999999.99999999
        and max_avg_latency_ms >= 0 and max_p95_latency_ms >= 0
        and version >= 0
    ),
    constraint chk_eval_gate_policy_text check (
        length(regexp_replace(name, '\s', '', 'g')) > 0
        and (category is null or length(regexp_replace(category, '\s', '', 'g')) > 0)
    ),
    constraint fk_eval_gate_policy_platform foreign key (platform_id)
        references midplat_platform (id)
);

create index if not exists idx_eval_gate_policy_scope
    on midplat_eval_gate_policy (platform_id, category, enabled);

create table if not exists midplat_eval_gate_decision (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    policy_id varchar(64) not null,
    policy_version bigint not null,
    conclusion varchar(16) not null,
    policy_snapshot_json text not null,
    evidence_json text not null,
    decided_at timestamp with time zone not null,
    constraint chk_eval_gate_decision_conclusion
        check (conclusion in ('PASS', 'FAIL', 'REVIEW_REQUIRED')),
    constraint chk_eval_gate_decision_version check (policy_version >= 0),
    constraint chk_eval_gate_decision_json_text check (
        length(regexp_replace(policy_snapshot_json, '\s', '', 'g')) > 0
        and length(regexp_replace(evidence_json, '\s', '', 'g')) > 0
    ),
    constraint fk_eval_gate_decision_run foreign key (run_id)
        references midplat_eval_run (id),
    constraint fk_eval_gate_decision_policy foreign key (policy_id)
        references midplat_eval_gate_policy (id)
);

create index if not exists idx_eval_gate_decision_run_decided
    on midplat_eval_gate_decision (run_id, decided_at desc, id asc);
create index if not exists idx_eval_gate_decision_policy_decided
    on midplat_eval_gate_decision (policy_id, decided_at desc, id asc);
