create table if not exists midplat_model (
    id varchar(64) primary key,
    name varchar(128) not null,
    kind varchar(32) not null,
    model_name varchar(128) not null,
    base_url varchar(512) not null,
    api_key varchar(256),
    ping_status varchar(32) not null default 'untested',
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_midplat_model_kind check (kind in ('llm', 'embedding', 'rerank'))
);

create table if not exists midplat_prompt (
    id varchar(64) primary key,
    name varchar(128) not null,
    slot varchar(32) not null,
    version_name varchar(32) not null default 'v1',
    body text not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0
);

create table if not exists midplat_platform (
    id varchar(64) primary key,
    name varchar(128) not null,
    entry_url varchar(512),
    icon varchar(64) not null default 'Boxes',
    consume boolean not null default true,
    token varchar(128),
    llm_model_id varchar(64),
    embedding_model_id varchar(64),
    rerank_model_id varchar(64),
    prompt_id varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_platform_llm foreign key (llm_model_id) references midplat_model (id),
    constraint fk_platform_emb foreign key (embedding_model_id) references midplat_model (id),
    constraint fk_platform_rr foreign key (rerank_model_id) references midplat_model (id),
    constraint fk_platform_prompt foreign key (prompt_id) references midplat_prompt (id)
);

create table if not exists midplat_menu (
    id varchar(64) primary key,
    parent_id varchar(64),
    name varchar(128) not null,
    route_name varchar(64) not null,
    icon varchar(64) not null default 'LayoutDashboard',
    platform_id varchar(64),
    sort_order integer not null default 0,
    visible boolean not null default true,
    locked boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_menu_parent foreign key (parent_id) references midplat_menu (id),
    constraint fk_menu_platform foreign key (platform_id) references midplat_platform (id) on delete set null
);

insert into midplat_model (id, name, kind, model_name, base_url, ping_status, version) values
('model-llm-ds', '知识库对话', 'llm', 'deepseek-v4-flash', 'http://127.0.0.1:4000/v1', 'untested', 0),
('model-llm-qwen', '问数对话', 'llm', 'qwen3.7-plus', 'http://127.0.0.1:4000/v1', 'untested', 0),
('model-llm-coder', '预标注对话', 'llm', 'qwen3-coder-next', 'http://127.0.0.1:4000/v1', 'untested', 0),
('model-emb-jina', '知识库向量', 'embedding', 'jina-embeddings-v5-text-small', 'http://127.0.0.1:4000/v1', 'untested', 0),
('model-rr-volc', '知识库重排', 'rerank', 'bge-reranker-v2-m3', 'https://ark.cn-beijing.volces.com/api/v3', 'untested', 0);

insert into midplat_prompt (id, name, slot, version_name, body, version) values
('prompt-rag', '知识库问答-默认', 'rag-qa', 'v1', '你是一个专业的知识库问答助手。请根据检索到的知识库内容回答用户问题。' || chr(10) || '仅基于检索内容回答，不要编造。', 0),
('prompt-prelabel', '预标注-病历字段', 'prelabel', 'v1', '你是临床文本预标注助手。根据已脱敏的问诊文本抽取约定字段。只输出字段结果。', 0),
('prompt-answer', '问数回答-医院口径', 'answer', 'v1', '你是医院运营分析助手。用已查出的指标结果作答，语气克制。不要发明 SQL。', 0);

insert into midplat_platform (id, name, entry_url, icon, consume, token, llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version) values
('plat-kb', '向量知识库', 'http://127.0.0.1:8900', 'Library', false, null, 'model-llm-ds', 'model-emb-jina', 'model-rr-volc', 'prompt-rag', 0),
('plat-an', '数据标注平台', 'http://127.0.0.1:8901', 'PenLine', false, null, 'model-llm-coder', null, null, 'prompt-prelabel', 0),
('plat-qa', '智能问数', 'http://127.0.0.1:8086/indicator/overview', 'LineChart', false, null, 'model-llm-qwen', null, null, 'prompt-answer', 0);

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version) values
('menu-entry', null, '平台入口', 'entry', 'Home', null, 10, true, true, 0),
('menu-platforms', null, '平台设置', 'platforms', 'Boxes', null, 20, true, false, 0),
('menu-settings', null, '系统设置', 'menus', 'Settings2', null, 30, true, true, 0),
('menu-settings-menus', 'menu-settings', '菜单设置', 'menus', 'Menu', null, 10, true, true, 0),
('menu-settings-models', 'menu-settings', '模型管理', 'models', 'Cpu', null, 20, true, false, 0),
('menu-settings-prompts', 'menu-settings', '提示词管理', 'prompts', 'FileText', null, 30, true, false, 0),
('menu-entry-kb', 'menu-entry', '向量知识库', 'entry-platform', 'Library', 'plat-kb', 10, true, false, 0),
('menu-entry-an', 'menu-entry', '数据标注平台', 'entry-platform', 'PenLine', 'plat-an', 20, true, false, 0),
('menu-entry-qa', 'menu-entry', '智能问数', 'entry-platform', 'LineChart', 'plat-qa', 30, true, false, 0);
