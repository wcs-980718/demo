create table if not exists exception_report (
    id              varchar(64)  not null primary key,
    platform_id     varchar(64)  not null,
    url             text         not null,
    method          varchar(10)  not null,
    query_params    text,
    request_body    text,
    status          integer,
    error_message   text         not null,
    occurred_at     timestamptz  not null,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    version         bigint       not null default 0
);

create index if not exists idx_exception_report_platform_time
    on exception_report (platform_id, created_at desc);
