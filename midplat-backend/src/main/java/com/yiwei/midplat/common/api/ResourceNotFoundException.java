package com.yiwei.midplat.common.api;

import java.net.URI;

public class ResourceNotFoundException extends RuntimeException {

    private final URI safeInstance;

    public ResourceNotFoundException(String message) {
        this(message, null);
    }

    public ResourceNotFoundException(String message, URI safeInstance) {
        super(message);
        this.safeInstance = safeInstance;
    }

    public URI getSafeInstance() {
        return safeInstance;
    }
}
