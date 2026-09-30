package com.yiwei.midplat.menu;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MenuPlatformRepository extends JpaRepository<MenuPlatform, MenuPlatform.MenuPlatformId> {

    List<MenuPlatform> findByMenuId(String menuId);

    List<MenuPlatform> findByPlatformIdIn(List<String> platformIds);

    boolean existsByPlatformIdAndMenuIdNot(String platformId, String menuId);

    void deleteByMenuId(String menuId);
}
