package com.yiwei.midplat.fusion;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CatalogRevisionRepository extends JpaRepository<CatalogRevision,String> {
    Optional<CatalogRevision> findByResourceTypeAndResourceIdAndContentHash(String type,String id,String hash);
    List<CatalogRevision> findByResourceTypeOrderByCreatedAtDesc(String type);
}
