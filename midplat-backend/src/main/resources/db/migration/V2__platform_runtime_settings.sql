alter table midplat_platform add column if not exists runtime_json text;

update midplat_platform
set runtime_json = '{"searchMode":"HYBRID","searchTopK":5,"ragTopK":20,"maxContextLength":8000,"statsListingLimit":50,"dataQueryDefaultLimit":10,"dataQueryMaxLimit":50,"rewriteEnabled":true,"rewriteMaxQueries":2,"rerankEnabled":true,"searchEnableRerank":false,"ragEnableRerank":true,"rerankEvidenceThreshold":35}'
where id = 'plat-kb' and runtime_json is null;
