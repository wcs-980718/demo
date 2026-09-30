package com.yiwei.midplat.platform;

import java.util.ArrayList;
import java.util.List;

final class PlatformApiCatalog {

    private PlatformApiCatalog() {}

    static List<PlatformApiItem> forPlatform(ManagedPlatform platform) {
        if (platform.isConsume()) {
            return consumeApis();
        }
        return switch (platform.getId()) {
            case "plat-kb" -> knowledgeApis();
            case "plat-an" -> annotationApis();
            case "plat-qa" -> indicatorApis();
            case "plat-root-cause" -> rootCauseReportApis();
            case "plat-fishbone" -> fishboneApis();
            default -> consumeApis();
        };
    }

    private static List<PlatformApiItem> consumeApis() {
        return List.of(
                item("rt-chat", "中台对话", "POST", "/api/runtime/chat", "新项目调用", true),
                item("rt-embed", "中台向量", "POST", "/api/runtime/embed", "新项目调用", true),
                item("rt-rerank", "中台重排", "POST", "/api/runtime/rerank", "新项目调用", true));
    }

    private static List<PlatformApiItem> knowledgeApis() {
        List<PlatformApiItem> items = new ArrayList<>();
        items.add(item("kb-settings-get", "运行时设置读取", "GET", "/api/settings", "中台拉配置", true));
        items.add(item("kb-settings-put", "运行时设置写入", "PUT", "/api/settings", "中台热更新", true));
        items.add(item("kb-list", "知识库列表", "GET", "/api/knowledge-bases", "标注发布中心下拉", true));
        items.add(item("kb-create", "创建知识库", "POST", "/api/knowledge-bases", "标注发布中心新建", true));
        items.add(item("kb-get", "知识库详情", "GET", "/api/knowledge-bases/{id}", "项目内管理", false));
        items.add(item("kb-update", "更新知识库", "PUT", "/api/knowledge-bases/{id}", "项目内管理", false));
        items.add(item("kb-delete", "删除知识库", "DELETE", "/api/knowledge-bases/{id}", "项目内管理", false));
        items.add(item("kb-docs", "文档列表", "GET", "/api/knowledge-bases/{kbId}/documents", "项目内管理", false));
        items.add(item("kb-upload", "上传文档", "POST", "/api/knowledge-bases/{kbId}/documents/upload", "标注发布上传", true));
        items.add(item("kb-process", "处理文档", "POST", "/api/documents/{id}/process", "项目内管理", false));
        items.add(item("kb-doc-get", "文档状态", "GET", "/api/documents/{id}", "标注发布轮询向量化", true));
        items.add(item("kb-doc-del", "删除文档", "DELETE", "/api/documents/{id}", "项目内管理", false));
        items.add(item("kb-search", "检索", "POST", "/api/search", "可单独给其他系统用", true));
        items.add(item("kb-rag", "RAG 问答", "POST", "/api/rag/ask", "可单独给其他系统用", true));
        items.add(item("kb-rag-stream", "RAG 流式问答", "POST", "/api/rag/ask/stream", "可单独给其他系统用", true));
        items.add(item("kb-history", "问答历史", "GET", "/api/rag/history", "项目内管理", false));
        items.add(item("kb-logs", "操作日志", "GET", "/api/operation-logs", "项目内管理", false));
        return items;
    }

    private static List<PlatformApiItem> annotationApis() {
        return List.of(
                item("an-settings-get", "预标注设置读取", "GET", "/api/settings", "中台拉配置", true),
                item("an-settings-put", "预标注设置写入", "PUT", "/api/settings", "中台热更新", true),
                item("an-health", "健康检查", "GET", "/api/health", "探活", false),
                item("an-projects", "项目列表", "GET", "/api/projects", "项目内管理", false),
                item("an-create-project", "创建项目", "POST", "/api/projects", "项目内管理", false),
                item("an-tasks", "任务列表", "GET", "/api/tasks", "项目内管理", false),
                item("an-create-task", "创建任务", "POST", "/api/tasks", "项目内管理", false),
                item("an-submit", "提交任务", "POST", "/api/tasks/{taskId}/submit", "项目内管理", false),
                item("an-prelabel", "预标注批次", "POST", "/api/prelabel-batches", "可单独给其他系统用", true),
                item("an-review-q", "复核队列", "GET", "/api/reviews/queue", "项目内管理", false),
                item("an-review", "提交复核", "POST", "/api/reviews", "项目内管理", false),
                item("an-publish", "发布任务", "POST", "/api/publish-jobs", "项目内管理", false));
    }

    private static List<PlatformApiItem> indicatorApis() {
        return List.of(
                item("qa-settings-get", "运行时设置读取", "GET", "/api/settings", "中台拉配置", true),
                item("qa-settings-put", "运行时设置写入", "PUT", "/api/settings", "中台热更新", true),
                item("qa-providers", "Provider 列表", "GET", "/api/llm-providers", "中台读配置", true),
                item("qa-activate", "激活 Provider", "PATCH", "/api/llm-providers/{providerId}/activate", "中台热更新", true),
                item("qa-chat", "智能问数", "POST", "/api/agent/chat/smart", "可单独给其他系统用", true),
                item("qa-stream", "智能问数流式", "POST", "/api/agent/chat/smart/stream", "可单独给其他系统用", true),
                item("qa-drill", "下钻解析", "POST", "/api/agent/drilldown/resolve", "可单独给其他系统用", true),
                item("qa-conv", "会话列表", "GET", "/api/conversations", "项目内管理", false),
                item("qa-sql", "生成 SQL", "POST", "/api/tools/generate_sql", "项目内管理", false));
    }

    private static List<PlatformApiItem> rootCauseReportApis() {
        return List.of(
                item("rc-list", "根因项目列表", "GET", "/api/closed-loop/improve/rootCause/list", "项目内管理", false),
                item("rc-query", "根因项目详情", "POST", "/api/closed-loop/improve/rootCause/project/{projectId}/query", "项目内管理", false),
                item("rc-context", "智能体分析上下文", "POST", "/api/closed-loop/improve/rootCause/agentContext", "生成报告前读取证据", false),
                item("rc-report", "根因报告生成", "POST", "/chat/completions", "root_cause 智能体 report_generation", true),
                item("rc-resources", "智能体资源读取", "GET", "/agent/resources", "读取 SOUL 与技能资源", false));
    }

    private static List<PlatformApiItem> fishboneApis() {
        return List.of(
                item("fb-context", "智能体分析上下文", "POST", "/api/closed-loop/improve/rootCause/agentContext", "生成鱼骨图前读取证据", false),
                item("fb-analyze", "鱼骨图智能分析", "POST", "/chat/completions", "root_cause 智能体 fishbone_analysis", true),
                item("fb-resources", "智能体资源读取", "GET", "/agent/resources", "读取 SOUL 与技能资源", false),
                item("fb-list", "鱼骨图明细读取", "GET", "/api/closed-loop/improve/rootCause/{rootCauseId}/item", "项目内管理", false),
                item("fb-save", "鱼骨图明细保存", "POST", "/api/closed-loop/improve/rootCause/{rootCauseId}/item", "项目内管理", false));
    }

    private static PlatformApiItem item(String id, String name, String method, String path, String note, boolean external) {
        return new PlatformApiItem(id, name, method, path, note, external);
    }
}
