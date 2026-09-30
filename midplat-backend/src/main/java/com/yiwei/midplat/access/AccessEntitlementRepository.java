package com.yiwei.midplat.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessEntitlementRepository extends JpaRepository<AccessEntitlement, String> {
    List<AccessEntitlement> findAllByClientIdOrderByCreatedAtAsc(String clientId);
    Optional<AccessEntitlement> findByClientIdAndResourceKindAndResourceIdAndAction(
            String clientId, String resourceKind, String resourceId, String action);
}
