package com.yiwei.midplat.access;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessGrantRepository extends JpaRepository<AccessGrant, String> {
    List<AccessGrant> findAllByCredentialIdOrderByCreatedAtAsc(String credentialId);
}
