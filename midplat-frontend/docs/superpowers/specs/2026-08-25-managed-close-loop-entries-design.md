# 闭环根因分析应用入口纳管设计

## 目标

将“根因报告分析”和“鱼骨图分析”纳入数智大脑的项目管理，但继续使用现有远程地址并在新标签页打开。两项能力属于同一个闭环分析项目，不登记成两个独立项目。

## 范围

- 新增一个受管项目：`闭环根因分析`，固定标识 `plat-close-loop`。
- 项目包含两个可维护的应用入口：
  - `root-cause-report`：根因报告分析，地址为 `http://company-portal.example.com:30200/close_loop/analysis/rootCause`。
  - `fishbone-analysis`：鱼骨图分析，地址为 `http://company-portal.example.com:30200/close_loop/analysis/rcTree`。
- 每个入口维护名称、说明、图标、访问地址、上线状态和排序。
- 首页从中台接口读取上述入口；点击入口仍通过新标签页打开远程地址。
- 项目继续展示并维护模型、提示词和接口配置。
- `close_loop` 本地代码不参与此次运行联动，也不替换远程地址。

## 不在范围内

- 不管理 `close_loop` 的本地启动、部署或健康检查。
- 不改变其他三个已接入项目的配置和首页行为。
- 暂不建设通用发布审批、权限或运维监控。

## 数据模型

新增 `midplat_platform_entry` 表，作为项目下应用入口的独立模块：

- `id varchar(64)`：入口稳定标识，主键。
- `platform_id varchar(64)`：所属项目，外键关联 `midplat_platform`。
- `name varchar(128)`：显示名称。
- `description varchar(512)`：首页和管理页说明。
- `icon varchar(64)`：Lucide 图标名称。
- `entry_url varchar(512)`：远程访问地址。
- `status varchar(32)`：`ONLINE` 或 `OFFLINE`。
- `sort_order integer`：项目内排序。
- 标准审计及乐观锁字段沿用现有实体约定。

`plat-close-loop` 继续使用现有 `midplat_platform` 的模型、提示词和接口配置能力。它属于中台管理既有 AI 项目的模式，不生成调用中台 runtime 的新项目凭证。

## 后端接口

在现有项目接口下增加入口子资源：

- `GET /api/platforms/{platformId}/entries`
- `POST /api/platforms/{platformId}/entries`
- `PATCH /api/platforms/{platformId}/entries/{entryId}`
- `DELETE /api/platforms/{platformId}/entries/{entryId}`

入口请求必须校验名称、合法的 HTTP/HTTPS 地址、状态和所属项目；不存在的项目或入口返回现有统一 404 响应。

## 前端行为

`闭环根因分析` 绑定到“智能问数”分类。进入项目配置后展示：

1. 项目名称与图标。
2. 模型配置，继续复用中台模型库。
3. 提示词配置，继续复用中台提示词库。
4. 接口清单，只登记 `lcfz` 代码中已确认存在的根因分析接口。
5. 应用入口列表及新增、编辑、停用、删除操作。
6. 每个入口的“新标签打开”按钮。

接口清单首批包括：

- `POST /api/closed-loop/ai/rootcause/report`
- `POST /api/closed-loop/ai/rootcause/report/stream`
- `POST /api/closed-loop/improve/rootCause/project/{projectId}/query`
- `POST /api/closed-loop/improve/rootCause/agentContext`
- `GET /api/closed-loop/improve/rootCause/list`
- `GET/POST /api/closed-loop/improve/rootCause/{rootCauseId}/item`

模型和提示词保存到中台配置。由于远程 `close_loop` 当前没有与中台模型、提示词绑定完全对应的通用热更新契约，页面必须显示“中台已记录，待目标项目同步”，不能显示“已热更新”。接口页展示真实存在的接口及开放标记，但不虚构调用量、成功率或消费者。

首页不再硬编码两个地址，而是查询 `plat-close-loop` 的在线入口并按 `sortOrder` 渲染。接口暂时不可用时保留当前两个远程入口作为只读回退，避免演示首页缺项；后台成功返回空列表时则不展示入口，确保“停用/删除”真实生效。

## 数据流与错误处理

管理页保存入口后刷新项目入口查询缓存；首页下一次加载读取最新配置。远程站点是否能打开由浏览器和目标网络决定，中台不宣称其运行健康。非法地址在前后端均拦截，后端校验为最终准则。

## 测试与验收

- 后端覆盖入口增删改查、非法 URL、项目不存在以及固定种子数据迁移。
- 前端覆盖在线入口转换、排序、接口失败回退、后台空列表隐藏、模型/提示词状态文案以及接口清单。
- 构建与现有测试全部通过。
- 手工验收：首页两张卡名称和说明保持不变，点击分别打开原远程地址；管理页可查看模型、提示词、真实接口和两个入口，修改地址或状态后刷新首页可见对应变化。

## 合并交付的既有确认项

本功能与当前工作区中已经确认的门户修改一起验收，不回退这些改动：

- 产品名称使用“数智大脑”，不显示简称。
- 保留横向菜单、背景粒子效果和明暗主题。
- “能力开放中心”调整为“开发中心”，包含模型管理、提示词管理、对外接口设置和框架内打开的智能体平台。
- 智能体平台从首页移除；首页隐藏“精选平台”。
- 首页普通项目卡点击后直接进入对应项目详情配置；根因报告分析和鱼骨图分析仍直接打开远程地址。
- 删除核心配置完整度、尝试下发和数据连接相关展示，并对内容较少的页面重新排版。
- 能力中心只保留已确认可实现的四项能力，调用量、成功率和消费者不使用虚构数据。
- 运行保障、交付管理及当前无法真实实现的功能继续隐藏。
