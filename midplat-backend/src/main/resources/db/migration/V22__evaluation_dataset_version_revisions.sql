alter table midplat_eval_dataset
    add column if not exists last_derived_at timestamp with time zone;

alter table midplat_eval_dataset_version
    add column if not exists item_revision integer not null default 0;
