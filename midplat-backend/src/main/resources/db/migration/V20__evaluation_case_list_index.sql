create index if not exists idx_eval_case_list_order
    on midplat_eval_case (lifecycle_status, updated_at desc, id desc);
