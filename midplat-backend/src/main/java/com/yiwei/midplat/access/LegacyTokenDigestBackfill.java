package com.yiwei.midplat.access;

import com.yiwei.midplat.platform.ManagedPlatform;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * W6 回填：为存量明文旧 token 计算受保护摘要（midplat_platform.legacy_token_digest）。
 * V34 只建了列与索引，存量行没有摘要时 legacy 摘要认证无法定位凭证——201 等已有环境的
 * 全部现存调用方都依赖这条路径。启动时幂等补算，只处理 token 非空且摘要为空的行；
 * 不输出 token 原文，不改变明文列（清空留到兼容期结束的独立迁移）。
 */
@Component
public class LegacyTokenDigestBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyTokenDigestBackfill.class);

    private final ManagedPlatformRepository platforms;
    private final AccessSecrets secrets;

    public LegacyTokenDigestBackfill(ManagedPlatformRepository platforms, AccessSecrets secrets) {
        this.platforms = platforms;
        this.secrets = secrets;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ManagedPlatform> backfilled = new ArrayList<>();
        for (ManagedPlatform platform : platforms.findAll()) {
            String token = platform.getToken();
            if (token == null || token.isBlank() || platform.getLegacyTokenDigest() != null) {
                continue;
            }
            platform.recordLegacyTokenDigest(secrets.legacyDigest(token));
            backfilled.add(platform);
        }
        if (!backfilled.isEmpty()) {
            platforms.saveAll(backfilled);
            log.info("Legacy token digest backfill completed: {} project(s) now have protected digests", backfilled.size());
        }
    }
}
