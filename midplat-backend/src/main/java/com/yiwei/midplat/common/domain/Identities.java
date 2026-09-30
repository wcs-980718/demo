package com.yiwei.midplat.common.domain;

import java.util.UUID;

public final class Identities {

    private Identities() {}

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
