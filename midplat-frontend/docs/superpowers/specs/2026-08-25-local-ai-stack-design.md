# 本地 AI 项目联动启动设计

## 目标

开发人员在 `midplat-frontend` 执行 `npm run dev` 时，中台及已接入的本地 AI 项目自动启动，使中台中的本地入口可以直接打开。此功能仅服务本地开发，不建设端口管理页面。

## 启动范围

联动启动以下进程：

| 模块 | 端口 | 启动入口 |
|---|---:|---|
| 中台前端 | 8000 | `midplat-frontend` |
| 中台后端 | 8090 | `midplat-backend` |
| 智能体平台前端 | 8001 | `智能体平台/agent-manager-web` |
| 智能体平台后端 | 8741 | `智能体平台/agent-manager/server-java` |
| 数据标注前端 | 8901 | `数据标注平台/data-annotation-platform` |
| 数据标注后端 | 8081 | `数据标注平台/data-annotatio-backend` |
| 知识库前端 | 8900 | `知识库/smart-knowledge-frontend-repo` |
| 知识库后端 | 8082 | `知识库/smart-knowledge-backend-repo` |
| 问数管理后端 | 5556 | 复用 `agent/scripts/local-stack.sh` |
| 问数 Agent | 8002 | 复用并参数化现有联动脚本 |
| 问数子应用 | 8086 | 复用 `agent/scripts/local-stack.sh` |
| 问数主应用 | 7300 | 复用 `agent/scripts/local-stack.sh` |

根因分析和鱼骨图继续打开 `company-portal.example.com:30200` 远程地址，不启动本地 `close_loop`。

## 启动模块

在中台前端增加一个本地开发启动模块。它的外部接口保持很小：

- `npm run dev`：启动完整本地栈。
- `npm run dev:portal`：只启动中台前端，供排障使用。
- `npm run dev:status`：检查各端口与入口。
- `npm run dev:stop`：停止由启动模块创建的进程。

模块启动前先检查端口。端口已被监听且对应 HTTP 健康检查通过时直接复用，不重复启动；端口被未知或不健康进程占用时明确报错并停止，不能随机换端口造成中台配置失效。

模块只记录自己创建的进程。退出或执行 `dev:stop` 时只停止这些进程，不终止启动前已存在的服务。日志写入中台前端的 `.dev-runtime/logs`，PID 与本次会话状态写入 `.dev-runtime/state`，该目录加入 Git 忽略。

## 端口参数化

- 数据标注后端使用 `SERVER_PORT=8081`；前端使用 `API_TARGET=http://127.0.0.1:8081`。
- 知识库后端使用 `SERVER_PORT=8082`；前端使用 `VITE_API_TARGET=http://127.0.0.1:8082`。
- 智能体平台前端使用 `PORT=8001` 和 `API_PROXY_TARGET=http://127.0.0.1:8741`。
- 问数脚本增加 `AGENT_PORT` 参数，默认仍为 8000；中台联动时传入 8002，并让前端的 `AGENT_API_TARGET`、`AGENT_PROVIDER_TARGET` 和健康检查统一引用该参数。

不得修改知识库、标注和智能体平台当前工作区中的用户代码来固定端口；中台启动模块通过进程环境变量覆盖。问数现有脚本需要做最小参数化修改，因为其 8000 端口目前被多处硬编码。

## 本地入口

中台本地配置使用稳定地址：

- 智能问数：`http://127.0.0.1:7300/indicator/overview`
- 知识库：`http://127.0.0.1:8900/smart-knowledge-frontend/`
- 数据标注：`http://127.0.0.1:8901/projects`
- 智能体平台：`http://127.0.0.1:8001/appMarket`

根因分析和鱼骨图的远程地址保持不变。

## 依赖与错误处理

启动模块在执行前检查 Node、npm/yarn、Java 17、Maven、curl、lsof 和 screen。缺少命令、项目目录或依赖时，输出具体模块和修复提示，不把失败服务报告为已启动。

问数项目继续遵守现有远程数据库配置校验。启动模块可以从显式的 `LOCAL_STACK_ENV_FILE` 加载环境变量；未提供时沿用当前 shell 环境。日志不得打印数据库密码或 API Key。

单个子项目启动失败时，中台前端仍可启动用于查看页面，但终端汇总必须标红失败模块并返回可定位的日志路径。`dev:status` 以端口和 HTTP 响应为依据，不以 PID 文件单独判断成功。

## 测试与验收

- 单元测试覆盖服务清单、固定端口、环境变量构造、端口占用分类和 PID 所有权。
- Shell 静态检查和脚本自身的状态命令通过。
- 在服务全部停止的情况下执行一次 `npm run dev`，确认所有入口可访问。
- 再次执行时确认健康服务被复用且没有端口冲突。
- 执行停止命令后，仅本次启动的服务被终止。
- 首页与开发中心中的四个本地入口均能打开；根因分析和鱼骨图仍打开远程地址。

