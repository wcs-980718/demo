-- 将 close_loop 中同一 root_cause 智能体的两个业务阶段登记为独立项目。
-- 两个项目共享 LLM，但分别维护入口、提示词和接口开放范围。

insert into midplat_prompt (id, name, slot, version_name, body, version)
select
    'prompt-root-cause-report',
    '根因报告-证据链',
    'root-cause-report',
    'v1',
    '你是医疗质量管理根因报告智能体。根据问题基本信息、当前闭环节点、指标快照和已确认鱼骨图生成结构化报告。
要求：
1. 严格采用结论-证据-建议结构，每条结论必须绑定输入中可核对的证据。
2. 不得编造部门、时间、指标、原因或处置结果；证据不足时明确标记待核实。
3. 主要根因必须来自已确认鱼骨图，改进建议必须能够追溯到具体根因。
4. 输出 Markdown，正文以“# 一、报告基本信息”开始。',
    0
where not exists (select 1 from midplat_prompt where id = 'prompt-root-cause-report');

insert into midplat_prompt (id, name, slot, version_name, body, version)
select
    'prompt-fishbone-analysis',
    '鱼骨图-结构化归因',
    'fishbone-analysis',
    'v1',
    '你是医疗质量管理鱼骨图分析智能体。根据问题基本信息、闭环节点、指标快照和护理不良事件原因明细生成 fishbone JSON。
要求：
1. 输出可渲染的 branches 数组，每个大要因包含 name 与 causes。
2. 大要因数量必须为偶数，每个大要因至少包含一个有效小要因，名称不得重复。
3. 只有输入证据充分支持时才能标记 primary；证据不足的原因必须标记为待验证。
4. 不绑定固定 6M 分类名称，不得补写输入中没有依据的业务原因。',
    0
where not exists (select 1 from midplat_prompt where id = 'prompt-fishbone-analysis');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-root-cause', '根因报告分析',
    'http://company-portal.example.com:30200/close_loop/pdca/rootCause',
    'FileSearch', false, null,
    'model-llm-qwen', null, null, 'prompt-root-cause-report', 0
where not exists (select 1 from midplat_platform where id = 'plat-root-cause');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-fishbone', '鱼骨图分析',
    'http://company-portal.example.com:30200/close_loop/pdca/rootCauseTree',
    'GitFork', false, null,
    'model-llm-qwen', null, null, 'prompt-fishbone-analysis', 0
where not exists (select 1 from midplat_platform where id = 'plat-fishbone');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-root-cause'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-root-cause');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-fishbone'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-fishbone');
