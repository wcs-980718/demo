package com.yiwei.midplat.common.domain;

public final class DomainAssertions {

    private DomainAssertions() {}

    public static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
