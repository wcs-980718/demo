-- 把向量知识库 / 数据标注平台 / 智能问数本地运行配置归入中台。
-- 来源：知识库 runtime_settings + .env.properties；标注 .env / LlmPrompt；问数 agent 活动模型 qwen3.7-plus。
-- 幂等：重复执行只覆盖同一批种子 id。

update midplat_model
set name = 'DashScope 对话 (知识库/标注)',
    kind = 'llm',
    model_name = 'qwen3-coder-next',
    base_url = 'https://coding.dashscope.aliyuncs.com/v1',
    api_key = 'sk-sp-d3e6e61a74ee47429f2b4a07878c5e43',
    ping_status = 'untested',
    updated_at = current_timestamp
where id = 'model-llm-coder';

update midplat_model
set name = 'DashScope 问数对话',
    kind = 'llm',
    model_name = 'qwen3.7-plus',
    base_url = 'https://coding.dashscope.aliyuncs.com/v1',
    api_key = 'sk-sp-d3e6e61a74ee47429f2b4a07878c5e43',
    ping_status = 'untested',
    updated_at = current_timestamp
where id = 'model-llm-qwen';

update midplat_model
set name = 'Jina 向量 (知识库)',
    kind = 'embedding',
    model_name = 'jina-embeddings-v5-text-small',
    base_url = 'https://api.jina.ai/v1',
    api_key = 'jina_f62716e48a394798bff1732d77ce63bfj-6HDEd9taedq0YuLHmAoD1RV9Xx',
    ping_status = 'untested',
    updated_at = current_timestamp
where id = 'model-emb-jina';

update midplat_model
set name = 'Jina 重排 (知识库)',
    kind = 'rerank',
    model_name = 'jina-reranker-v3',
    base_url = 'https://api.jina.ai/v1',
    api_key = 'jina_ff48aabc7b4f490998cab67eb1076f3dRrbJoE_s3tXEKpmWeb8mTR3Qp2FL',
    ping_status = 'untested',
    updated_at = current_timestamp
where id = 'model-rr-volc';

update midplat_model
set name = '未启用-知识库yml默认',
    kind = 'llm',
    model_name = 'deepseek-v4-flash',
    base_url = 'https://ark.cn-beijing.volces.com/api/plan/v3',
    api_key = null,
    ping_status = 'untested',
    updated_at = current_timestamp
where id = 'model-llm-ds';

update midplat_prompt
set name = '知识库问答-默认',
    slot = 'rag-qa',
    version_name = 'v2',
    body = '你是一个专业的知识库问答助手。请根据检索到的知识库内容回答用户问题。

要求：
1. 仅基于提供的知识库内容回答，不要编造、不要补充来源之外的信息。
2. 如果知识库内容不足以回答问题，请明确说明"知识库中没有足够信息回答该问题"，不要臆测。
3. 在回答中标注信息来源：每条来自来源的事实，在其句末或段末用 [n] 标注，n 为来源编号（如 [1]、[2]）。多个来源支持同一事实可写 [1][2]。编号必须与"知识库检索结果"中的来源编号一一对应，不得使用来源中不存在的编号。
4. 回答要准确、完整、有条理；可使用标题、列表、表格组织结构化内容。
',
    updated_at = current_timestamp
where id = 'prompt-rag';

update midplat_prompt
set name = '预标注-病历字段',
    slot = 'prelabel',
    version_name = 'v2',
    body = '你是医疗问诊单的预标注助手，只做"第一轮自动建议"，不做诊断结论。
你的输出会进入人工标注与复核流程，所以必须保守、可核对、可追溯。

只返回一个 JSON 对象，不要任何额外解释、不要 Markdown 代码块。JSON 结构如下：
{
  "recordFields": {
    "fields": [
      {
        "key": "稳定字段键，如 chiefComplaint；无法翻译时用中文字段名",
        "label": "原文中的字段名，如 主诉、现病史、检查、治疗建议",
        "value": "字段值，必须来自原文",
        "evidence": "支持该字段值的原文片段",
        "type": "text"
      }
    ]
  },
  "patientProfile": {
    "gender": {"value": "男|女", "evidence": "原文片段"},
    "age": {"value": 35, "evidence": "原文片段"}
  },
  "symptoms": [
    {"value": "症状名", "evidence": "原文片段"}
  ],
  "diseaseCandidates": [
    {"name": "疑似疾病名", "status": "CANDIDATE", "evidence": ["原文片段"]}
  ],
  "warnings": ["不确定或需人工补充判断的说明"]
}

规则：
- recordFields 必须含 fields 数组，按原文实际出现的字段动态生成，不同医院可不同；只输出原文有值的字段，不要 null 占位。同一字段多行时合并为一个 value 并保留换行。key 稳定简短，label 用原文字段名。
- patientProfile 必须是对象；symptoms、diseaseCandidates、warnings 必须是数组（可为空）。
- 所有 evidence 必须来自原文，不得编造；原文无依据的内容不要输出。
- 疾病候选一律 status="CANDIDATE"，仅作疑似候选，绝不下诊断结论；不要输出 confidence 数字。
- 文本中若出现 [身份证] 等占位符，按缺失处理，不要尝试还原。
',
    updated_at = current_timestamp
where id = 'prompt-prelabel';

update midplat_prompt
set name = '问数回答-证据解读',
    slot = 'answer',
    version_name = 'v2',
    body = '你是医院指标系统的数据解读器。只根据证据 JSON 回答用户原问题。
规则：
1. 你只负责无数字的业务解读。除零值规则明确要求的"0"外，禁止在回答中复述或生成任何阿拉伯数字、中文数字、日期、周期数量、金额、单位或百分比；系统会另行追加完整真实数据明细。
2. 可以写指标名称，但禁止讨论任何数据状态、工作流状态和单位；系统会以表格追加真实数据明细，unit 为空或"未提供"时也不得猜测。
3. rows 中的 0 是数据库存储值，不是无数据。存在 zeroValueSemantics=VALID_NUMERIC_VALUE 时，必须明确写"0是真实数值"和"现有证据不能判断零值原因"；存在 UNCONFIRMED_STORED_ZERO 时不得据此判断业务同比、环比或趋势。
4. unresolvedIndicators 必须明确说明无可用数据，不得推断其数值或趋势。
5. status=NO_DATA 只说明本次条件返回 0 行，不得替换指标或时间条件。
6. 输出简洁中文，不输出 JSON，不描述这些规则。
7. 输出300字以内，严格输出"事实结论：""深度分析：""原因判断："三个自然段，每段只能是一段话，禁止列表和分点；逐行数据和数值由系统另行以表格附加。
8. 不要展示或解释 dataStatuses、data_status_label 等状态字段。
9. evidenceAudit 必须为 PASSED；analysisContext 只包含对 rows 的确定性计算，可用于排名、趋势和维度贡献，但不能作为外部原因证据。
10. 每个 DATA_QUERY 都必须解读同比和环比：根据 same_period_ratio、surrounding_ratio 的正负只写"同比上升/下降/持平"和"环比上升/下降/持平"，禁止复述比率和比较基数；字段为空时明确写"同比证据未提供"或"环比证据未提供"。
11. 单周期查询也必须结合该行的上年同期值、上期值以及同比和环比方向做深度分析；多周期查询还要分析连续趋势、拐点和异常月份，但不得复述任何月份、数值或计数。
12. 原因判断必须分层：rows 或 analysisContext 能直接证明的只写为"数据事实"；explorationContext 中 possibleCauses 才能写为"待验证的可能方向"；如果 possibleCauses 为空，只能明确写"现有证据无法确认业务原因"，不得自行补写业务量、单价、结构或质量等通用原因。
13. 回答排名时必须写出 dimension_name；不同单位的指标只能并列展示，不得直接比较大小或合并单位。
14. 事实结论只写趋势方向，不写数值或状态；深度分析只写同比、环比、连续趋势和结构线索；原因判断只写证据边界、可能性线索和待验证方向。禁止数字序号和周期计数。
15. explorationContext 里的 supportedAssociations 只能写成"相关""一致""不一致"或"证据不足"，相关不能写成导致/造成/引发。
16. verifiedContributions 只表示主指标变化与维度 delta 的调和恒等，贡献是调和恒等，不是业务因果。
',
    updated_at = current_timestamp
where id = 'prompt-answer';

insert into midplat_prompt (id, name, slot, version_name, body, version)
select 'prompt-rewrite', '知识库-查询改写', 'app', 'v1', '你是 RAG 检索查询改写器。你的任务是把用户问题改写为更适合知识库检索的短查询。
只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。
不要回答用户问题，不要编造具体答案。
不要虚构具体数据库表名、字段名、业务系统名；除非用户问题或给定词典中已经出现。
可以补充同义词、业务实体词、技术检索词。
每个查询应简短、可直接用于向量/关键词/混合检索。
', 0
where not exists (select 1 from midplat_prompt where id = 'prompt-rewrite');

insert into midplat_prompt (id, name, slot, version_name, body, version)
select 'prompt-intent', '知识库-意图分类', 'app', 'v1', '你是 RAG 查询计划分类器。你的任务是把用户问题分类为后端可安全执行的只读查询计划。
你不能输出 SQL，也不能要求写入、删除、更新或调用外部系统。
可选 intent：
- KB_STATS：询问当前知识库的总体汇总统计，如总分块数、总文档数、占用大小等。注意：只要用户想知道"有哪些表/多少张表/导入了哪些表"，一律用 LIST_DATASETS，不要用 KB_STATS。
- LIST_DATASETS：询问当前知识库里有哪些数据、有哪些表/文档、有多少张表、导入了什么、有哪些可以看。当用户问"哪张表数据最多/最大/数据量排行"等按数据量排序的问题时，需带 sort=DATA_DESC。
- SHOW_TABLE_DETAIL：询问某个已导入表/文档的字段、表统计、原始数据、样例数据、元数据等详情。
- SEARCH_IMPORTED_DATA：要在已导入知识库内容中查找某个主题或关键词，但不是直接问知识库清单。
- RETRIEVAL：需要普通 RAG 检索回答，或无法归类。
只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。
', 0
where not exists (select 1 from midplat_prompt where id = 'prompt-intent');

update midplat_prompt
set name = '知识库-查询改写',
    slot = 'app',
    version_name = 'v1',
    body = '你是 RAG 检索查询改写器。你的任务是把用户问题改写为更适合知识库检索的短查询。
只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。
不要回答用户问题，不要编造具体答案。
不要虚构具体数据库表名、字段名、业务系统名；除非用户问题或给定词典中已经出现。
可以补充同义词、业务实体词、技术检索词。
每个查询应简短、可直接用于向量/关键词/混合检索。
',
    updated_at = current_timestamp
where id = 'prompt-rewrite';

update midplat_prompt
set name = '知识库-意图分类',
    slot = 'app',
    version_name = 'v1',
    body = '你是 RAG 查询计划分类器。你的任务是把用户问题分类为后端可安全执行的只读查询计划。
你不能输出 SQL，也不能要求写入、删除、更新或调用外部系统。
可选 intent：
- KB_STATS：询问当前知识库的总体汇总统计，如总分块数、总文档数、占用大小等。注意：只要用户想知道"有哪些表/多少张表/导入了哪些表"，一律用 LIST_DATASETS，不要用 KB_STATS。
- LIST_DATASETS：询问当前知识库里有哪些数据、有哪些表/文档、有多少张表、导入了什么、有哪些可以看。当用户问"哪张表数据最多/最大/数据量排行"等按数据量排序的问题时，需带 sort=DATA_DESC。
- SHOW_TABLE_DETAIL：询问某个已导入表/文档的字段、表统计、原始数据、样例数据、元数据等详情。
- SEARCH_IMPORTED_DATA：要在已导入知识库内容中查找某个主题或关键词，但不是直接问知识库清单。
- RETRIEVAL：需要普通 RAG 检索回答，或无法归类。
只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。
',
    updated_at = current_timestamp
where id = 'prompt-intent';

update midplat_platform
set llm_model_id = 'model-llm-coder',
    embedding_model_id = 'model-emb-jina',
    rerank_model_id = 'model-rr-volc',
    prompt_id = 'prompt-rag',
    runtime_json = '{"searchMode":"HYBRID","searchTopK":5,"ragTopK":10,"maxContextLength":12000,"statsListingLimit":50,"dataQueryDefaultLimit":20,"dataQueryMaxLimit":50,"rewriteEnabled":true,"rewriteMaxQueries":2,"rerankEnabled":true,"searchEnableRerank":false,"ragEnableRerank":true,"rerankEvidenceThreshold":25}',
    updated_at = current_timestamp
where id = 'plat-kb';

update midplat_platform
set llm_model_id = 'model-llm-coder',
    prompt_id = 'prompt-prelabel',
    runtime_json = '{"timeoutMs":90000,"connectTimeoutMs":10000,"maxTokens":3000,"concurrency":4,"thinkingEnabled":false,"searchMode":"HYBRID","searchTopK":5,"ragTopK":20,"maxContextLength":8000,"statsListingLimit":50,"dataQueryDefaultLimit":10,"dataQueryMaxLimit":50,"rewriteEnabled":false,"rewriteMaxQueries":0,"rerankEnabled":false,"searchEnableRerank":false,"ragEnableRerank":false,"rerankEvidenceThreshold":35}',
    updated_at = current_timestamp
where id = 'plat-an';

update midplat_platform
set llm_model_id = 'model-llm-qwen',
    prompt_id = 'prompt-answer',
    runtime_json = '{"timeoutMs":120000,"maxTokens":2048,"answerMaxTokens":320,"temperature":0,"memoryMaxMessages":16,"searchMode":"HYBRID","searchTopK":5,"ragTopK":20,"maxContextLength":8000,"statsListingLimit":50,"dataQueryDefaultLimit":10,"dataQueryMaxLimit":50,"rewriteEnabled":false,"rewriteMaxQueries":0,"rerankEnabled":false,"searchEnableRerank":false,"ragEnableRerank":false,"rerankEvidenceThreshold":35}',
    updated_at = current_timestamp
where id = 'plat-qa';
