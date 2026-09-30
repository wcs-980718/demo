package com.yiwei.midplat.platform;

public record PlatformApiItem(String id, String name, String method, String path, String note, boolean external) {
    public PlatformApiItem withExternal(boolean value) {
        return new PlatformApiItem(id, name, method, path, note, value);
    }

    public PlatformApiItem withDetails(String name, String method, String path, String note, boolean external) {
        return new PlatformApiItem(id, name, method, path, note, external);
    }
}
