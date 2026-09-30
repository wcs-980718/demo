package com.yiwei.midplat.openapi;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenApiPathTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^/}]+)\\}");

    private OpenApiPathTemplate() {}

    static String fill(String template, String extraPath) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        List<String> parts = extraSegments(extraPath);
        if (names.isEmpty()) {
            if (!parts.isEmpty()) {
                throw new IllegalArgumentException("该接口不接受额外路径");
            }
            return template;
        }
        if (parts.size() != names.size()) {
            throw new IllegalArgumentException("接口路径参数不完整");
        }
        String filled = template;
        for (int i = 0; i < names.size(); i++) {
            filled = filled.replace("{" + names.get(i) + "}", encode(parts.get(i)));
        }
        return filled;
    }

    static URI target(String entryUrl, String path, String query) {
        if (entryUrl == null || entryUrl.isBlank()) {
            throw new IllegalArgumentException("归属项目未配置入口地址，无法转发");
        }
        String base = entryUrl.trim().replaceAll("/+$", "");
        String resolved = path.startsWith("/") ? path : "/" + path;
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return URI.create(base + resolved + suffix);
    }

    private static List<String> extraSegments(String extraPath) {
        List<String> parts = new ArrayList<>();
        if (extraPath == null || extraPath.isBlank()) {
            return parts;
        }
        for (String part : extraPath.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
