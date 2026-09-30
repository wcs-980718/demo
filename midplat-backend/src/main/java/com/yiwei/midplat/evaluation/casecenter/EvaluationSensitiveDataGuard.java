package com.yiwei.midplat.evaluation.casecenter;

import java.util.List;
import java.util.regex.Pattern;

final class EvaluationSensitiveDataGuard {

    static final int MAX_CASE_CONTENT_LENGTH = 20_000;

    private static final String MASK = "[已脱敏]";
    private static final List<SensitiveRule> RULES = List.of(
            new SensitiveRule(
                    "手机号",
                    Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")),
            new SensitiveRule(
                    "身份证号",
                    Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])")),
            new SensitiveRule(
                    "访问令牌",
                    Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{10,}")),
            new SensitiveRule(
                    "中台平台令牌",
                    Pattern.compile("(?i)(?<![A-Za-z0-9_-])sk-mid-[0-9a-f]{10}(?![A-Za-z0-9_-])")),
            new SensitiveRule(
                    "访问凭据",
                    Pattern.compile(
                            "(?i)(?<![\\p{L}\\p{N}_-])"
                                    + "[\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)[\"']?"
                                    + "\\s*[:=]\\s*"
                                    + "(?:\"[^\"\\r\\n]{1,512}\"|'[^'\\r\\n]{1,512}'|[^\\s,;}\\]\\r\\n]{1,512})")),
            new SensitiveRule(
                    "JWT 令牌",
                    Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{8,}")),
            new SensitiveRule(
                    "私钥",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new SensitiveRule(
                    "云访问密钥",
                    Pattern.compile("(?<![A-Z0-9])AKIA[A-Z0-9]{16}(?![A-Z0-9])")));

    private EvaluationSensitiveDataGuard() {}

    static void requireDesensitized(String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (SensitiveRule rule : RULES) {
            if (rule.pattern().matcher(value).find()) {
                throw new IllegalArgumentException(
                        "案例字段 " + field + " 疑似包含未脱敏的" + rule.label());
            }
        }
    }

    static void requireMaxLength(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "案例字段 " + field + " 长度不能超过 " + maxLength + " 个字符");
        }
    }

    static String sanitizeForAudit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value;
        for (SensitiveRule rule : RULES) {
            sanitized = rule.pattern().matcher(sanitized).replaceAll(MASK);
        }
        return sanitized.length() <= maxLength
                ? sanitized
                : sanitized.substring(0, maxLength) + "…";
    }

    private record SensitiveRule(String label, Pattern pattern) {}
}
