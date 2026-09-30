create table if not exists midplat_eval_dataset (
    id varchar(64) primary key,
    name varchar(128) not null,
    description text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0
);

create table if not exists midplat_eval_dataset_version (
    id varchar(64) primary key,
    dataset_id varchar(64) not null,
    version_no integer not null,
    status varchar(16) not null,
    snapshot_hash varchar(64),
    frozen_at timestamp with time zone,
    draft_slot integer,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_eval_dataset_version_dataset foreign key (dataset_id)
        references midplat_eval_dataset (id),
    constraint uq_eval_dataset_version_no unique (dataset_id, version_no),
    constraint uq_eval_dataset_version_draft unique (dataset_id, draft_slot),
    constraint chk_eval_dataset_version_status check (status in ('DRAFT','FROZEN','ARCHIVED')),
    constraint chk_eval_dataset_version_draft_slot check (
        (status = 'DRAFT' and draft_slot = 1)
        or (status in ('FROZEN','ARCHIVED') and draft_slot is null)
    ),
    constraint chk_eval_dataset_version_frozen_state check (
        (status = 'DRAFT' and snapshot_hash is null and frozen_at is null)
        or (status in ('FROZEN','ARCHIVED') and snapshot_hash is not null and frozen_at is not null)
    )
);

create table if not exists midplat_eval_dataset_item (
    id varchar(64) primary key,
    version_id varchar(64) not null,
    case_id varchar(64) not null,
    order_no integer not null,
    snapshot_name varchar(128),
    snapshot_category varchar(64),
    snapshot_severity varchar(16),
    snapshot_input_text text,
    snapshot_expected_json text,
    snapshot_evaluator_type varchar(32),
    snapshot_content_hash varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_eval_dataset_item_version foreign key (version_id)
        references midplat_eval_dataset_version (id),
    constraint fk_eval_dataset_item_case foreign key (case_id)
        references midplat_eval_case (id),
    constraint uq_eval_dataset_item_case unique (version_id, case_id),
    constraint uq_eval_dataset_item_order unique (version_id, order_no)
);

create index if not exists idx_eval_dataset_version_dataset
    on midplat_eval_dataset_version (dataset_id, version_no desc);
create index if not exists idx_eval_dataset_item_version_order
    on midplat_eval_dataset_item (version_id, order_no asc);
