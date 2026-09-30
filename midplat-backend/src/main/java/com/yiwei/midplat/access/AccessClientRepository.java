package com.yiwei.midplat.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessClientRepository extends JpaRepository<AccessClient, String> {
    Optional<AccessClient> findByConsumerIdAndCode(String consumerId, String code);
    List<AccessClient> findAllByProjectIdOrderByCreatedAtAsc(String projectId);
    List<AccessClient> findAllByConsumerIdOrderByCreatedAtAsc(String consumerId);
}
