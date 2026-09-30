package com.yiwei.midplat.prompt;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PromptRepository extends JpaRepository<Prompt, String> {
    List<Prompt> findAllByOrderByNameAsc();
}
