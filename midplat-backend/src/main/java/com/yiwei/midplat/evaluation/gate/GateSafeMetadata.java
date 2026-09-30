package com.yiwei.midplat.evaluation.gate;

import java.util.regex.Pattern;

/** 门禁策略、决策快照与证据共用的单一安全元数据边界。 */
final class GateSafeMetadata {

    private static final String REDACTED_CASE_NAME = "案例名称已脱敏";
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(?:"
            + "(?<![a-z0-9_-])bearer\\s+[a-z0-9._~+/=-]+"
            + "|(?<![a-z0-9_-])eyj[a-z0-9_-]{6,}\\.[a-z0-9_-]{6,}\\.[a-z0-9_-]{6,}"
            + "|-----begin(?: [a-z]+)* private key-----"
            + "|(?<![a-z0-9])akia[a-z0-9]{16}(?![a-z0-9])"
            + "|(?<![a-z0-9_-])sk-[a-z0-9_-]+(?![a-z0-9_-])"
            + "|(?<![a-z0-9_-])[\"']?(?:api(?:[_-]?(?:key|token))?|access(?:[_-]?token)?|"
            + "refresh(?:[_-]?token)?|password|passwd|secret)[\"']?\\s*[:=]\\s*[\"']?[^\\s,;}\\]\\r\\n]{1,512}"
            + ")");
    private static final Pattern BODY_FIELD = Pattern.compile(
            "(?i)[\"']?(?:input(?:[_-]?text)?|expected(?:[_-]?json)?|actual[_-]?output|"
                    + "candidate[_-]?output[_-]?summary|output[_-]?summary|trace[_-]?summary)[\"']?\\s*[:=]");

    private GateSafeMetadata() {}

    static String required(String value, String field, int maxLength) {
        if (!isSafe(value, maxLength)) throw new IllegalArgumentException(field + " 无效");
        return value.trim();
    }

    static String optional(String value, String field, int maxLength) {
        return value == null ? null : required(value, field, maxLength);
    }

    static boolean isSafe(String value, int maxLength) {
        if (value == null || value.length() > maxLength || value.trim().isEmpty()
                || value.trim().length() > maxLength) {
            return false;
        }
        if (value.codePoints().anyMatch(GateSafeMetadata::isInvisibleOrDirectionalControl)) {
            return false;
        }
        return !CREDENTIAL.matcher(value).find() && !BODY_FIELD.matcher(value).find();
    }

    private static boolean isInvisibleOrDirectionalControl(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    static String redactCaseName(String value) {
        return isSafe(value, 128) ? value.trim() : REDACTED_CASE_NAME;
    }
}
