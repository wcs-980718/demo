package com.yiwei.midplat.exceptionlog;

import java.util.Locale;

/**
 * 异常归类：把原始（多为英文）异常信息翻译成中文类别与说明。
 * 读取时动态计算，不落库——存量记录同样生效，项目侧无需重新部署。
 * fault=false 表示非服务故障（客户端行为/验证记录），前端灰色展示。
 */
public final class ErrorCategory {

    public record View(String category, String label, String detail, boolean fault) {}

    private ErrorCategory() {}

    public static View classify(Integer status, String errorMessage, String url) {
        String message = errorMessage == null ? "" : errorMessage.toLowerCase(Locale.ROOT);
        String target = url == null ? "" : url.toLowerCase(Locale.ROOT);
        boolean modelCall = target.contains("chat/completions")
                || target.contains("embeddings")
                || target.contains("/proxy/v1");

        if (message.contains("[smoke-test]") || message.contains("[security-smoke]")) {
            return new View("smoke-test", "验证记录",
                    "部署/联调时人工埋下的验证记录，可忽略。", false);
        }
        if (message.contains("asyncrequestnotusableexception") || message.contains("disconnected client")
                || message.contains("clientabortexception") || message.contains("broken pipe")) {
            return new View("client-disconnected", "客户端中断",
                    "用户关闭页面或停止了流式回答导致连接断开，服务本身正常，无需处理。", false);
        }
        if (message.contains("httprequestmethodnotsupportedexception") || message.contains("request method '")) {
            return new View("method-not-allowed", "请求方法错误",
                    "调用方使用了接口不支持的 HTTP 方法（例如对 POST 接口发 GET），请检查调用方式。", false);
        }
        if ((status != null && status == 401) || message.contains("unauthorizedexception") || message.contains("凭证无效")) {
            return new View("credential", "凭证无效",
                    "调用凭证缺失或已失效，请到中台对应项目的「接入凭证」更新后重试。", true);
        }
        if ((status != null && status == 429) || message.contains("too many requests")) {
            return new View("rate-limited", "限流/配额不足",
                    modelCall
                            ? "模型服务返回 429，请求频率或额度不足。"
                            : "请求过于频繁或配额不足（429），请稍后重试。", true);
        }
        if (message.contains("httptimeoutexception") || message.contains("timed out") || message.contains("timeout")
                || message.contains("sockettimeoutexception") || (status != null && status == 504)) {
            return new View("timeout", modelCall ? "模型调用超时" : "调用超时",
                    modelCall
                            ? "模型服务响应超时，可能是模型负载高或网络波动。"
                            : "下游服务响应超时，请稍后重试或检查下游服务状态。", true);
        }
        if (message.contains("connection refused") || message.contains("connectexception")
                || message.contains("unknownhost") || message.contains("sslhandshake")) {
            return new View("network", modelCall ? "模型服务不可达" : "网络不通",
                    modelCall
                            ? "连不上模型服务（连接被拒/DNS/SSL 失败），请检查模型地址与网络。"
                            : "目标服务连接不上（连接被拒/DNS/SSL 失败），请检查目标地址与网络。", true);
        }
        if (status != null && status >= 500) {
            return new View("server-error", modelCall ? "模型服务错误" : "服务端错误",
                    modelCall
                            ? "模型服务返回了服务端错误，请查看原始信息与模型服务日志。"
                            : "接口处理时发生内部异常，请结合原始错误信息与服务日志排查。", true);
        }
        if (status != null && status >= 400) {
            return new View("client-error", "请求参数问题",
                    "调用方请求参数不合法（4xx），请检查请求内容。", false);
        }
        return new View("unknown", "未分类",
                "未能自动识别的错误类型，请查看原始错误信息判断。", true);
    }
}
