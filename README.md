# AI中台项目 源码（脱敏导出）

本仓库为源码演示导出，包含两个子工程：

- `midplat-backend/` —— 中台后端（Spring Boot + PostgreSQL + Flyway）
- `midplat-frontend/` —— 中台前端（React + Umi + Ant Design）

## 说明

- 本导出已脱敏：数据库地址、账号、密码均已改为环境变量，内部服务地址已替换为示例域名（`*.example.com`）。
- 运行时所需配置通过环境变量注入，入口参考 `midplat-backend/src/main/resources/application.yml`。
- 前端本地开发代理目标通过 `PORTAL_TARGET` 环境变量指定，参考 `midplat-frontend/.umirc.ts`。
