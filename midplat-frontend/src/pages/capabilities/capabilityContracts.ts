export type CapabilityParameter = {
  name: string;
  location: 'Header' | 'Body' | 'Path' | 'Query';
  type: string;
  required: boolean;
  description: string;
  rules?: string;
  example?: string;
};

export type CapabilityResponseField = {
  name: string;
  type: string;
  description: string;
};

export type CapabilityError = {
  status: string;
  code: string;
  description: string;
};

export type CapabilityContract = {
  authentication: string;
  contentType: string;
  sourceNote: string;
  headers: CapabilityParameter[];
  requestParameters: CapabilityParameter[];
  requestExample: string;
  responseFields: CapabilityResponseField[];
  responseExample: string;
  errors: CapabilityError[];
};

const COMMON_HEADERS: CapabilityParameter[] = [
  {
    name: 'Authorization',
    location: 'Header',
    type: 'string',
    required: true,
    description: '调用方项目在「接入凭证」中签发的中台凭证。经中台网关请求时必须携带，不带或无效返回 401。',
    rules: '格式固定为 Bearer <凭证>，凭证形如 sk-mid-********',
    example: 'Bearer sk-mid-********',
  },
  {
    name: 'Content-Type',
    location: 'Header',
    type: 'string',
    required: true,
    description: '请求体媒体类型。',
    rules: '固定为 application/json',
    example: 'application/json',
  },
];

const KNOWLEDGE_ERRORS: CapabilityError[] = [
  { status: '400', code: 'VALIDATION_FAILED', description: '必填字段缺失、字段格式或取值不合法。' },
  { status: '400', code: 'MALFORMED_REQUEST', description: 'JSON 请求体无法解析。' },
  { status: '404', code: 'KNOWLEDGE_BASE_NOT_FOUND', description: '指定知识库不存在或已被删除。' },
  { status: '415', code: 'UNSUPPORTED_MEDIA_TYPE', description: 'Content-Type 不是 application/json。' },
  { status: '502', code: 'EXTERNAL_DEPENDENCY / MILVUS_OPERATION_FAILED', description: '向量库、Embedding、Rerank 或 LLM 等依赖调用失败。' },
  { status: '500', code: 'UNEXPECTED_ERROR', description: '未预期的服务端错误，响应不会暴露内部敏感信息。' },
];

export const CAPABILITY_CONTRACTS: Record<string, CapabilityContract> = {
  'cap-kb-answer': {
    authentication: '应用凭证（Bearer Token）',
    contentType: 'application/json',
    sourceNote: '参数来自知识库 RagRequest；开放层负责应用鉴权，来源接口为 POST /api/rag/ask。',
    headers: COMMON_HEADERS,
    requestParameters: [
      { name: 'query', location: 'Body', type: 'string', required: true, description: '需要基于知识库回答的问题。', rules: '不能为空', example: '院内输血管理制度有哪些核心要求？' },
      { name: 'knowledgeBaseIds', location: 'Body', type: 'array<number>', required: true, description: '参与检索的知识库 ID 列表。', rules: '至少提供 1 个有效知识库 ID', example: '[9]' },
      { name: 'topK', location: 'Body', type: 'integer', required: false, description: '进入问答上下文的候选片段数量。', rules: '1–50；不传时使用项目运行参数', example: '5' },
      { name: 'mode', location: 'Body', type: 'enum', required: false, description: '知识检索模式。', rules: 'VECTOR / KEYWORD / HYBRID；默认 HYBRID', example: 'HYBRID' },
      { name: 'enableRerank', location: 'Body', type: 'boolean', required: false, description: '是否对召回结果启用重排。', rules: '默认 true；需项目已绑定 Rerank 模型', example: 'true' },
    ],
    requestExample: `{
  "query": "院内输血管理制度有哪些核心要求？",
  "knowledgeBaseIds": [9],
  "topK": 5,
  "mode": "HYBRID",
  "enableRerank": true
}`,
    responseFields: [
      { name: 'code', type: 'integer', description: '业务状态码，成功为 200。' },
      { name: 'message', type: 'string', description: '处理结果说明，成功为 success。' },
      { name: 'data.query', type: 'string', description: '服务端实际处理的问题。' },
      { name: 'data.answer', type: 'string', description: '基于检索证据生成的回答。' },
      { name: 'data.model', type: 'string', description: '本次生成回答使用的模型。' },
      { name: 'data.sources[]', type: 'array<object>', description: '引用来源片段列表。' },
      { name: 'data.sources[].chunkId', type: 'string', description: '向量库分块标识。' },
      { name: 'data.sources[].score', type: 'number', description: '检索或重排相关度分数。' },
      { name: 'data.sources[].text', type: 'string', description: '命中的文本片段。' },
      { name: 'data.sources[].chunkDbId', type: 'number', description: '分块数据库记录 ID。' },
      { name: 'data.sources[].documentId', type: 'number', description: '来源文档 ID。' },
      { name: 'data.sources[].documentName', type: 'string', description: '来源文档名称。' },
      { name: 'data.sources[].chunkIndex', type: 'number', description: '片段在文档中的序号。' },
      { name: 'data.sources[].sourceType', type: 'string', description: '来源类型，例如 database、docx、pdf、txt。' },
      { name: 'data.sources[].metadataJson', type: 'string', description: '来源片段附加元数据 JSON。' },
      { name: 'data.sources[].createdAt', type: 'string', description: '片段创建时间。' },
      { name: 'data.sources[].knowledgeBaseId', type: 'number', description: '所属知识库 ID。' },
      { name: 'data.tables[]', type: 'array<object>', description: '命中数据库来源时返回的表格洞察。' },
      { name: 'data.tables[].documentId / documentName / tableName', type: 'mixed', description: '数据库来源文档与表标识。' },
      { name: 'data.tables[].tableData', type: 'object', description: '结构化表格数据。' },
      { name: 'data.tables[].tableData.columns', type: 'array<string>', description: '表头列名。' },
      { name: 'data.tables[].tableData.rows', type: 'array<object>', description: '数据行，每行是列名到单元格值的映射。' },
      { name: 'data.tables[].tableData.totalRows', type: 'integer', description: '数据总行数。' },
      { name: 'data.tables[].tableData.tableName', type: 'string', description: '来源数据库表名。' },
      { name: 'data.tables[].fields[]', type: 'array<object>', description: '字段画像列表。' },
      { name: 'data.tables[].fields[].fieldName', type: 'string', description: '字段名称。' },
      { name: 'data.tables[].fields[].inferredType', type: 'enum', description: '推断类型：NUMBER / DATE / CATEGORY / TEXT。' },
      { name: 'data.tables[].fields[].distinctCount', type: 'integer', description: '字段去重值数量。' },
      { name: 'data.tables[].fields[].sampleValues', type: 'array<string>', description: '字段样本值，最多 5 个。' },
      { name: 'data.tables[].overview[]', type: 'array<object>', description: '数据概览指标列表。' },
      { name: 'data.tables[].overview[].label / value / unit', type: 'string', description: '概览指标名称、格式化值和可选单位。' },
      { name: 'data.tables[].charts[]', type: 'array<object>', description: '图表规格列表。' },
      { name: 'data.tables[].charts[].chartType', type: 'enum', description: 'bar / line / pie。' },
      { name: 'data.tables[].charts[].title', type: 'string', description: '图表标题。' },
      { name: 'data.tables[].charts[].xAxisField / yAxisField', type: 'string', description: '图表坐标轴字段。' },
      { name: 'data.tables[].charts[].data', type: 'array<object>', description: '图表数据。' },
      { name: 'data.tables[].quality[]', type: 'array<object>', description: '数据质量指标列表。' },
      { name: 'data.tables[].quality[].label / value / unit', type: 'string', description: '质量指标名称、格式化值和可选单位。' },
      { name: 'data.tables[].quality[].level', type: 'enum', description: 'good / warn / bad / info。' },
      { name: 'data.tables[].quality[].description', type: 'string', description: '质量指标解释。' },
      { name: 'data.documents[]', type: 'array<object>', description: '命中文档来源时返回的文档洞察。' },
      { name: 'data.documents[].documentId / documentName / sourceType', type: 'mixed', description: '文档来源标识。' },
      { name: 'data.documents[].snippets[]', type: 'array<object>', description: '文档片段列表。' },
      { name: 'data.documents[].snippets[].chunkIndex', type: 'integer', description: '片段在文档中的序号。' },
      { name: 'data.documents[].snippets[].sectionPath', type: 'string', description: '片段所在章节路径。' },
      { name: 'data.documents[].snippets[].rawText', type: 'string', description: '原始片段文本。' },
      { name: 'data.documents[].snippets[].cleanedText', type: 'string', description: '清洗后的片段文本。' },
      { name: 'data.documents[].snippets[].summary', type: 'string', description: '片段的一句话摘要。' },
      { name: 'data.documents[].snippets[].contentType', type: 'enum', description: 'SQL / TREE / MARKDOWN / TEXT。' },
    ],
    responseExample: `{
  "code": 200,
  "message": "success",
  "data": {
    "query": "院内输血管理制度有哪些核心要求？",
    "answer": "根据院内制度，核心要求包括……",
    "model": "qwen3-coder-next",
    "sources": [{
      "chunkId": "kb-9-doc-128-chunk-3",
      "score": 0.91,
      "text": "输血申请必须完成适应证评估……",
      "chunkDbId": 3021,
      "documentId": 128,
      "documentName": "临床输血管理制度.docx",
      "chunkIndex": 3,
      "sourceType": "docx",
      "metadataJson": "{}",
      "createdAt": "2026-08-20T09:30:00",
      "knowledgeBaseId": 9
    }],
    "tables": [],
    "documents": []
  }
}`,
    errors: KNOWLEDGE_ERRORS,
  },
  'cap-kb-search': {
    authentication: '应用凭证（Bearer Token）',
    contentType: 'application/json',
    sourceNote: '参数来自知识库 SearchRequest；开放层负责应用鉴权，来源接口为 POST /api/search。',
    headers: COMMON_HEADERS,
    requestParameters: [
      { name: 'query', location: 'Body', type: 'string', required: true, description: '检索关键词或自然语言查询。', rules: '不能为空', example: '输血申请审批流程' },
      { name: 'knowledgeBaseIds', location: 'Body', type: 'array<number>', required: true, description: '参与检索的知识库 ID 列表。', rules: '至少提供 1 个有效知识库 ID', example: '[9, 12]' },
      { name: 'topK', location: 'Body', type: 'integer', required: false, description: '最多返回的片段数量。', rules: '1–50；不传时使用项目运行参数', example: '10' },
      { name: 'scoreThreshold', location: 'Body', type: 'number', required: false, description: '最低相关度阈值。', rules: '默认 0.0', example: '0.35' },
      { name: 'mode', location: 'Body', type: 'enum', required: false, description: '知识检索模式。', rules: 'VECTOR / KEYWORD / HYBRID', example: 'HYBRID' },
      { name: 'enableRerank', location: 'Body', type: 'boolean', required: false, description: '是否启用重排。', rules: '需项目已绑定 Rerank 模型', example: 'true' },
      { name: 'sourceType', location: 'Body', type: 'string', required: false, description: '按来源类型过滤。', rules: '常用值：database / docx / pdf / txt；不传则不过滤', example: 'pdf' },
    ],
    requestExample: `{
  "query": "输血申请审批流程",
  "knowledgeBaseIds": [9, 12],
  "topK": 10,
  "scoreThreshold": 0.35,
  "mode": "HYBRID",
  "enableRerank": true,
  "sourceType": "pdf"
}`,
    responseFields: [
      { name: 'code', type: 'integer', description: '业务状态码，成功为 200。' },
      { name: 'message', type: 'string', description: '处理结果说明，成功为 success。' },
      { name: 'data[]', type: 'array<object>', description: '按相关度返回的知识片段。' },
      { name: 'data[].chunkId', type: 'string', description: '向量库分块标识。' },
      { name: 'data[].score', type: 'number', description: '检索或重排相关度分数。' },
      { name: 'data[].text', type: 'string', description: '命中的文本片段。' },
      { name: 'data[].chunkDbId', type: 'number', description: '分块数据库记录 ID。' },
      { name: 'data[].documentId', type: 'number', description: '来源文档 ID。' },
      { name: 'data[].documentName', type: 'string', description: '来源文档名称。' },
      { name: 'data[].chunkIndex', type: 'number', description: '片段在文档中的序号。' },
      { name: 'data[].sourceType', type: 'string', description: '来源类型。' },
      { name: 'data[].metadataJson', type: 'string', description: '附加元数据 JSON。' },
      { name: 'data[].createdAt', type: 'string', description: '片段创建时间。' },
      { name: 'data[].knowledgeBaseId', type: 'number', description: '所属知识库 ID。' },
    ],
    responseExample: `{
  "code": 200,
  "message": "success",
  "data": [{
    "chunkId": "kb-9-doc-128-chunk-3",
    "score": 0.91,
    "text": "输血申请由经治医师提交……",
    "chunkDbId": 3021,
    "documentId": 128,
    "documentName": "临床输血管理制度.pdf",
    "chunkIndex": 3,
    "sourceType": "pdf",
    "metadataJson": "{}",
    "createdAt": "2026-08-20T09:30:00",
    "knowledgeBaseId": 9
  }]
}`,
    errors: KNOWLEDGE_ERRORS,
  },
  'cap-qa-chat': {
    authentication: '应用凭证（Bearer Token）+ 机构授权范围',
    contentType: 'application/json',
    sourceNote: '请求字段来自智能问数 ChatRequest；开放层将应用身份映射到受控机构权限，来源接口为 POST /api/agent/chat/smart。',
    headers: COMMON_HEADERS,
    requestParameters: [
      { name: 'message', location: 'Body', type: 'string', required: true, description: '医院运营或指标问题。', rules: '不能为空，最大 4000 字符', example: '查询最近半年医疗收入趋势' },
      { name: 'session_id', location: 'Body', type: 'string', required: false, description: '会话标识，用于连续问答上下文隔离。', example: 'session-20260824-001' },
      { name: 'history', location: 'Body', type: 'array<object>', required: false, description: '历史消息列表。', rules: '最多 20 条；默认 []', example: '[{"role":"user","content":"查询医疗收入"}]' },
      { name: 'history[].role', location: 'Body', type: 'string', required: true, description: '历史消息角色。', rules: '不能为空，通常为 user / assistant', example: 'user' },
      { name: 'history[].content', location: 'Body', type: 'string', required: true, description: '历史消息正文。', rules: '不能为空', example: '查询医疗收入' },
      { name: 'history[].agent_result', location: 'Body', type: 'object', required: false, description: '上一轮 Agent 的结构化结果，用于受控上下文继承。', rules: '默认 {}', example: '{}' },
      { name: 'context', location: 'Body', type: 'object', required: false, description: '页面、路由和查询上下文。', rules: '默认空对象', example: '{}' },
      { name: 'context.currentRoute', location: 'Body', type: 'string', required: false, description: '调用方当前页面路由。', rules: '最大 512 字符', example: '/indicator/overview' },
      { name: 'context.pageSnapshot', location: 'Body', type: 'object', required: false, description: '当前页面快照。institutionId 会被规范化并进行授权校验。', rules: '最多 20 个键', example: '{"institutionId":"1001"}' },
      { name: 'context.allowedRoutes', location: 'Body', type: 'array<string>', required: false, description: '允许 Agent 建议跳转的路由白名单。', rules: '最多 200 条，每条最大 512 字符', example: '["/indicator/overview"]' },
      { name: 'context.querySnapshot', location: 'Body', type: 'object', required: false, description: '结构化查询上下文。', rules: '未提供时使用空对象', example: '{}' },
      { name: 'context.querySnapshot.institutionId', location: 'Body', type: 'string', required: false, description: '机构 ID，必须位于应用获授权的机构范围内。', example: '1001' },
      { name: 'context.querySnapshot.indicatorId', location: 'Body', type: 'string', required: false, description: '单个指标 ID。', example: 'medical_income' },
      { name: 'context.querySnapshot.indicatorIds', location: 'Body', type: 'array<string>', required: false, description: '多个指标 ID；会与 indicatorId 合并去重。', example: '["medical_income","outpatient_count"]' },
      { name: 'context.querySnapshot.namespaceId', location: 'Body', type: 'string', required: false, description: '指标命名空间 ID。', example: 'hospital_operation' },
      { name: 'context.querySnapshot.dataSource', location: 'Body', type: 'string', required: false, description: '数据源标识。', example: 'indicator' },
      { name: 'context.querySnapshot.factSource', location: 'Body', type: 'string', required: false, description: '事实数据来源标识。', example: 'im_data_month' },
      { name: 'context.querySnapshot.roleId', location: 'Body', type: 'string', required: false, description: '当前角色 ID。', example: 'role-admin' },
      { name: 'context.querySnapshot.roleName', location: 'Body', type: 'string', required: false, description: '当前角色名称。', example: '运营管理' },
      { name: 'context.querySnapshot.startTime', location: 'Body', type: 'string', required: false, description: '查询开始时间。', example: '2026-01' },
      { name: 'context.querySnapshot.endTime', location: 'Body', type: 'string', required: false, description: '查询结束时间。', example: '2026-06' },
      { name: 'context.querySnapshot.period', location: 'Body', type: 'string', required: false, description: '业务期间。', example: '2026-H1' },
      { name: 'context.querySnapshot.granularity', location: 'Body', type: 'string', required: false, description: '时间粒度。', example: 'MONTH' },
      { name: 'context.querySnapshot.comparisonMode', location: 'Body', type: 'string', required: false, description: '同比、环比等比较方式。', example: 'YOY' },
      { name: 'grain', location: 'Body', type: 'string', required: false, description: '显式指定分析粒度，服务端会转为大写。', example: 'MONTH' },
      { name: 'skip_llm', location: 'Body', type: 'boolean', required: false, description: '跳过 LLM 的受控联调开关。', rules: '默认 false；生产调用应保持 false', example: 'false' },
    ],
    requestExample: `{
  "message": "查询最近半年医疗收入趋势",
  "session_id": "session-20260824-001",
  "history": [],
  "context": {
    "currentRoute": "/indicator/overview",
    "pageSnapshot": { "institutionId": "1001" },
    "allowedRoutes": ["/indicator/overview"],
    "querySnapshot": {
      "institutionId": "1001",
      "indicatorId": "medical_income",
      "indicatorIds": ["medical_income"],
      "namespaceId": "hospital_operation",
      "dataSource": "indicator",
      "factSource": "im_data_month",
      "roleId": "role-admin",
      "roleName": "运营管理",
      "startTime": "2026-01",
      "endTime": "2026-06",
      "period": "2026-H1",
      "granularity": "MONTH",
      "comparisonMode": "YOY"
    }
  },
  "grain": "MONTH",
  "skip_llm": false
}`,
    responseFields: [
      { name: 'answer', type: 'string', description: '基于受控查询结果生成的业务回答。' },
      { name: 'tool_calls[]', type: 'array<object>', description: '本次调用的受控工具、参数和返回结果。' },
      { name: 'analysis_trace', type: 'object', description: '分析过程的结构化轨迹。' },
      { name: 'intent', type: 'object', description: '识别出的业务意图和路由结果。' },
      { name: 'facts[]', type: 'array<object>', description: '从真实查询行提取的事实，含 id、label、value、displayValue、unit、period、evidenceIds。' },
      { name: 'insights[]', type: 'array<object>', description: '基于事实生成的结构化洞察。' },
      { name: 'limitations[]', type: 'array<object>', description: '数据范围、缺失指标和质量边界。' },
      { name: 'visualizations[]', type: 'array<object>', description: '由真实查询行构建的图表规格，含 id、type、title、unit、data 等字段。' },
      { name: 'resultStatus', type: 'enum', description: '整体结果状态：OK / NO_DATA / ERROR。' },
      { name: 'serviceStatus', type: 'enum', description: '服务状态：ready / error。' },
    ],
    responseExample: `{
  "answer": "最近半年医疗收入总体呈上升趋势……",
  "tool_calls": [{
    "tool": "queryIndicatorData",
    "result": { "status": "OK", "rowCount": 6, "rows": [{ "period": "2026-06", "current_value": 12800000 }] }
  }],
  "analysis_trace": {},
  "intent": { "intent": "data_query" },
  "facts": [{ "id": "evidence-0", "label": "2026-06", "value": 12800000, "displayValue": "1280万元", "unit": "万元", "period": "2026-06", "evidenceIds": ["evidence-0"] }],
  "insights": [],
  "limitations": [],
  "visualizations": [{ "id": "governed-analytics-0", "source": "governed-analytics", "type": "line", "title": "医疗收入趋势", "unit": "万元", "data": [{ "label": "2026-06", "value": 1280, "period": "2026-06", "evidenceId": "evidence-0" }] }],
  "resultStatus": "OK",
  "serviceStatus": "ready"
}`,
    errors: [
      { status: '400', code: 'SQL_GUARD_REJECTED', description: '生成的 SQL 未通过只读或上下文安全校验。' },
      { status: '401', code: 'AUTHENTICATION_REQUIRED', description: '应用凭证或映射的登录会话无效。' },
      { status: '403', code: 'INSTITUTION_FORBIDDEN', description: '应用无权访问请求中声明的机构。' },
      { status: '422', code: 'VALIDATION_ERROR', description: 'message、history 或 context 等字段不符合约束。' },
      { status: '503', code: 'IDENTITY_SERVICE_UNAVAILABLE', description: '身份服务暂时不可用。' },
      { status: '503', code: 'INSTITUTION_PERMISSION_SERVICE_UNAVAILABLE', description: '机构权限服务暂时不可用。' },
      { status: '500', code: 'INTERNAL_ERROR', description: '未预期的服务端错误。' },
    ],
  },
  'cap-an-prelabel': {
    authentication: '应用凭证（Bearer Token）',
    contentType: 'application/json',
    sourceNote: '参数来自数据标注平台 StartBatchRequest；任务异步执行，来源接口为 POST /api/prelabel-batches。',
    headers: COMMON_HEADERS,
    requestParameters: [
      { name: 'datasetId', location: 'Body', type: 'string', required: true, description: '需要执行预标注的数据集 ID。', rules: '不能为空；数据集必须已存在', example: 'dataset-20260824-01' },
      { name: 'overwrite', location: 'Body', type: 'boolean', required: false, description: '是否覆盖已有 LLM 建议。人工标注结果永远不会被覆盖。', rules: '默认 false', example: 'false' },
      { name: 'requestedBy', location: 'Body', type: 'string', required: false, description: '发起人或调用系统标识，用于审计。', example: 'research-data-workbench' },
    ],
    requestExample: `{
  "datasetId": "dataset-20260824-01",
  "overwrite": false,
  "requestedBy": "research-data-workbench"
}`,
    responseFields: [
      { name: 'success', type: 'boolean', description: '请求是否成功受理。' },
      { name: 'message', type: 'string | null', description: '处理结果说明。' },
      { name: 'data.id', type: 'string', description: '预标注批次 ID，后续用于查询进度。' },
      { name: 'data.projectId', type: 'string', description: '所属标注项目 ID。' },
      { name: 'data.datasetId', type: 'string', description: '目标数据集 ID。' },
      { name: 'data.status', type: 'enum', description: 'PENDING / RUNNING / SUCCEEDED / FAILED / PARTIAL。' },
      { name: 'data.overwriteExisting', type: 'boolean', description: '本批次是否覆盖已有 LLM 建议。' },
      { name: 'data.totalCount', type: 'integer', description: '符合条件的任务总数。' },
      { name: 'data.processedCount', type: 'integer', description: '已处理任务数。' },
      { name: 'data.succeededCount', type: 'integer', description: '成功任务数。' },
      { name: 'data.failedCount', type: 'integer', description: '失败任务数。' },
      { name: 'data.errorMessage', type: 'string | null', description: '批次错误摘要。' },
      { name: 'data.requestedBy', type: 'string | null', description: '发起人或调用系统标识。' },
    ],
    responseExample: `{
  "success": true,
  "data": {
    "id": "batch-8f42a1",
    "projectId": "project-medical-record",
    "datasetId": "dataset-20260824-01",
    "status": "PENDING",
    "overwriteExisting": false,
    "totalCount": 128,
    "processedCount": 0,
    "succeededCount": 0,
    "failedCount": 0,
    "errorMessage": null,
    "requestedBy": "research-data-workbench"
  },
  "message": null
}`,
    errors: [
      { status: '400', code: 'Validation Error', description: 'datasetId 为空或请求字段校验失败；响应为 RFC 7807 ProblemDetail。' },
      { status: '404', code: 'Resource Not Found', description: '数据集不存在；detail 中返回资源说明。' },
      { status: '500', code: 'Internal Server Error', description: '未预期的服务端错误；生产环境返回脱敏信息。' },
    ],
  },
};

const CONTRACT_BY_ROUTE: Record<string, keyof typeof CAPABILITY_CONTRACTS> = {
  'POST /api/rag/ask': 'cap-kb-answer',
  'POST /api/search': 'cap-kb-search',
  'POST /api/agent/chat/smart': 'cap-qa-chat',
  'POST /api/prelabel-batches': 'cap-an-prelabel',
};

const GATEWAY_ERRORS: CapabilityError[] = [
  { status: '401', code: 'UNAUTHORIZED', description: '缺少或无效的中台凭证。请求头必须带 Authorization: Bearer <项目凭证>。' },
  { status: '403', code: 'FORBIDDEN', description: '该接口未对其他项目开放。' },
  { status: '404', code: 'NOT_FOUND', description: '接口不存在或已被删除。' },
];

function mergeErrors(prefix: CapabilityError[], rest: CapabilityError[]) {
  const seen = new Set(prefix.map((item) => `${item.status}:${item.code}`));
  return [...prefix, ...rest.filter((item) => !seen.has(`${item.status}:${item.code}`))];
}

function withGatewayAuth(contract: CapabilityContract): CapabilityContract {
  return {
    ...contract,
    authentication: '调用方项目接入凭证（Bearer Token），必填',
    headers: contract.headers.map((header) => (
      header.name === 'Authorization'
        ? {
            ...header,
            required: true,
            description: '调用方项目在「接入凭证」中签发的中台凭证。经中台网关请求时必须携带，不带或无效返回 401。',
            rules: '格式固定为 Bearer <凭证>，凭证形如 sk-mid-********',
            example: 'Bearer sk-mid-********',
          }
        : header
    )),
    errors: mergeErrors(GATEWAY_ERRORS, contract.errors),
  };
}

export function contractForApi(method: string, path: string): CapabilityContract {
  const key = `${method.toUpperCase()} ${path}`;
  const known = CONTRACT_BY_ROUTE[key];
  if (known) {
    return withGatewayAuth(CAPABILITY_CONTRACTS[known]);
  }
  const placeholders = [...path.matchAll(/\{([^/}]+)\}/g)].map((match) => match[1]);
  const pathParams: CapabilityParameter[] = placeholders.map((name) => ({
    name,
    location: 'Path',
    type: 'string',
    required: true,
    description: `路径参数，接在中台地址后面，例如 /api/open/项目ID/接口ID/{${name}}。`,
    example: name === 'id' || name.toLowerCase().includes('id') ? '9' : name,
  }));
  const isGet = method.toUpperCase() === 'GET';
  return withGatewayAuth({
    authentication: '调用方项目接入凭证（Bearer Token），必填',
    contentType: isGet ? '无' : 'application/json 或原请求 Content-Type',
    sourceNote: '中台校验调用方凭证后，按原方法、查询参数和请求体转发到来源接口。',
    headers: isGet ? [COMMON_HEADERS[0]] : COMMON_HEADERS,
    requestParameters: pathParams,
    requestExample: pathParams.length
      ? `{${pathParams.map((item) => `"${item.name}": "${item.example ?? ''}"`).join(', ')}}`
      : '{}',
    responseFields: [
      { name: 'body', type: 'json', description: '来源系统原始响应，中台不改写。' },
    ],
    responseExample: '{ "code": 0, "data": {} }',
    errors: GATEWAY_ERRORS,
  });
}

export function emptyCapabilityContract(publicPath: string): CapabilityContract {
  return {
    authentication: '应用凭证（Bearer Token）',
    contentType: 'application/json',
    sourceNote: '草稿能力尚未补充来源接口契约，发布前必须完善并完成联调审核。',
    headers: COMMON_HEADERS,
    requestParameters: [],
    requestExample: `{
  "请在发布前补充请求参数": "${publicPath}"
}`,
    responseFields: [],
    responseExample: `{
  "请在发布前补充响应契约": true
}`,
    errors: [],
  };
}
