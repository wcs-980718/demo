# Jenkins 构建

Jenkins 自由风格任务的 Execute shell 使用：

```bash
export tag="${BUILD_TAG:-$(date '+%Y%m%d%H%M')}"
bash devops/pipeline.sh
```

镜像仓库认证优先由构建机预先完成；如需任务内登录，通过 Jenkins Credentials Binding 注入
`REGISTRY_USERNAME` 和 `REGISTRY_PASSWORD`。脚本不会保存账号密码。

## 浏览器入口跨域配置

中台请求携带会话凭据，API 必须返回明确的允许来源，不能返回
`Access-Control-Allow-Origin: *`。通过 `MIDPLAT_FUSION_WEB_ORIGINS`
（对应 `midplat.fusion.web-origins`）配置逗号分隔的受信任页面来源。

当前门户 `http://portal.example.com:30200` 和中台独立入口
`http://portal.example.com:31010` 均需包含在名单中；配置只填写协议、主机和端口，
不包含页面路径。未设置该配置时默认包含上述两个入口及本地 8000 端口。
配置为空或包含通配符时应用拒绝启动。

这项配置控制浏览器跨域访问，不会启用融合管理或更换任何项目的数据库连接。

## 中台内置智能体执行模块

智能体执行代码、数据库迁移及测试已纳入本仓库的 `com.agenthubfusion` 包。
`mvn package` 生成的 `midplat-backend.jar` 同时包含管理后端与执行模块，
由现有中台 Jenkins 任务构建和发布一个 `midplat-backend` 镜像。
不需要拉取或发布原 `agent-manager` 仓库。

默认启动管理后端；同 Pod 的执行容器设置 `MIDPLAT_COMPONENT=agent-runtime`，
使用相同的中台镜像版本。执行模块仍通过 `FUSION_AGENT_DB_*` 连接独立数据源，
其 `fusion` 配置使用单独的迁移目录和 JDBC 自动提交，不继承管理后端的 JPA 事务设置。
执行接口仅由中台内部调用，管理浏览器入口不变。

历史发布按 SHA-256 固定运行包及 JVM 基础镜像。已有持久卷应保留；迁移到新卷时，
把原 `_artifacts/*.jar` 放入构建上下文的 `fusion-runtime-history/`，
用 `Dockerfile.runtime-history` 及 `BASE_IMAGE=<本次中台镜像>` 生成带历史包的新版本。
该兼容镜像使用历史运行环境的不可变 Maven/Temurin 17 镜像摘要，
将本次构建的中台 JAR 和启动脚本加入原环境。`FUSION_RUNTIME_IMAGE_DIGEST`
记录该基础镜像的配置摘要，与既有发布快照一致；不能仅改此变量来冒充其他运行环境。
Helm 的 `midplatFusion.runtime.runtimeImageDigest` 也应填写上述基础镜像配置摘要，
避免部署参数中的 `local-jvm` 默认值覆盖兼容镜像的环境标识。
管理容器与执行容器均填写该最终版本。启动脚本逐个校验历史包和持久卷中的摘要，
缺失或损坏的历史包不会由新代码替代。历史包及业务数据不提交到 Git。
