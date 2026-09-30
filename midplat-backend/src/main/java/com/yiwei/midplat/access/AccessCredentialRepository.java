package com.yiwei.midplat.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessCredentialRepository extends JpaRepository<AccessCredential, String> {
    Optional<AccessCredential> findByKeyId(String keyId);
    List<AccessCredential> findAllByClientIdOrderByCreatedAtDesc(String clientId);
}
