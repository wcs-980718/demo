package com.yiwei.midplat.menu;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MenuItemRepository extends JpaRepository<MenuItem, String> {
    List<MenuItem> findAllByOrderBySortOrderAscNameAsc();
    List<MenuItem> findByParentIdOrderBySortOrderAscNameAsc(String parentId);
}
