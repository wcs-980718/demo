package com.yiwei.midplat.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface AiModelRepository extends JpaRepository<AiModel, String> {
    List<AiModel> findAllByOrderByNameAsc();
}
