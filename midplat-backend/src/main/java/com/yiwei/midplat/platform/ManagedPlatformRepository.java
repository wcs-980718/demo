package com.yiwei.midplat.platform;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagedPlatformRepository extends JpaRepository<ManagedPlatform, String> {
    List<ManagedPlatform> findAllByOrderByNameAsc();

    /** Read-only compatibility for tests/tools; authentication resolves via digest, never plaintext. */
    @Deprecated
    java.util.Optional<ManagedPlatform> findByToken(String token);

    java.util.Optional<ManagedPlatform> findByLegacyTokenDigest(String digest);

    @Query("""
            select (count(p) > 0) from ManagedPlatform p
             where p.llmModelId = :modelId
                or p.embeddingModelId = :modelId
                or p.rerankModelId = :modelId
            """)
    boolean existsBoundToModel(@Param("modelId") String modelId);

    @Query("""
            select (count(p) > 0) from ManagedPlatform p
             where p.promptId = :promptId
            """)
    boolean existsBoundToPrompt(@Param("promptId") String promptId);
}
