# Jenkins 构建

Jenkins 自由风格任务的 Execute shell 使用：

```bash
export tag="${BUILD_TAG:-$(date '+%Y%m%d%H%M')}"
bash devops/pipeline.sh
```

镜像仓库认证优先由构建机预先完成；如需任务内登录，通过 Jenkins Credentials Binding 注入
`REGISTRY_USERNAME` 和 `REGISTRY_PASSWORD`。脚本不会保存账号密码。
