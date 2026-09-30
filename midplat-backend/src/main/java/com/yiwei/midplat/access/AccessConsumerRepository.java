package com.yiwei.midplat.access;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessConsumerRepository extends JpaRepository<AccessConsumer, String> {
    Optional<AccessConsumer> findByCode(String code);
    boolean existsByCode(String code);
}
