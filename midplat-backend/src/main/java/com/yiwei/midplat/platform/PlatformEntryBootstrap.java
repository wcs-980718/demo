package com.yiwei.midplat.platform;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PlatformEntryBootstrap implements ApplicationRunner {

    private final ManagedPlatformRepository repository;
    private final String knowledgeEntryUrl;
    private final String annotationEntryUrl;
    private final String indicatorEntryUrl;

    PlatformEntryBootstrap(
            ManagedPlatformRepository repository,
            @Value("${midplat.platform-bootstrap.knowledge-entry-url:}") String knowledgeEntryUrl,
            @Value("${midplat.platform-bootstrap.annotation-entry-url:}") String annotationEntryUrl,
            @Value("${midplat.platform-bootstrap.indicator-entry-url:}") String indicatorEntryUrl) {
        this.repository = repository;
        this.knowledgeEntryUrl = knowledgeEntryUrl;
        this.annotationEntryUrl = annotationEntryUrl;
        this.indicatorEntryUrl = indicatorEntryUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initialize("plat-kb", knowledgeEntryUrl);
        initialize("plat-an", annotationEntryUrl);
        initialize("plat-qa", indicatorEntryUrl);
    }

    private void initialize(String platformId, String configuredEntryUrl) {
        if (configuredEntryUrl == null || configuredEntryUrl.isBlank()) {
            return;
        }
        repository.findById(platformId)
                .filter(platform -> isLoopbackOrBlank(platform.getEntryUrl()))
                .ifPresent(platform -> platform.updateEntryUrl(configuredEntryUrl.trim()));
    }

    private static boolean isLoopbackOrBlank(String entryUrl) {
        if (entryUrl == null || entryUrl.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(entryUrl.trim()).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(normalized)
                    || "127.0.0.1".equals(normalized)
                    || "::1".equals(normalized);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
