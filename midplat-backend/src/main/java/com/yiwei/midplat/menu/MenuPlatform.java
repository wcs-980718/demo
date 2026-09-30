package com.yiwei.midplat.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "midplat_menu_platform")
@IdClass(MenuPlatform.MenuPlatformId.class)
public class MenuPlatform {

    @Id
    @Column(name = "menu_id", length = 64)
    private String menuId;

    @Id
    @Column(name = "platform_id", length = 64)
    private String platformId;

    protected MenuPlatform() {}

    public MenuPlatform(String menuId, String platformId) {
        this.menuId = menuId;
        this.platformId = platformId;
    }

    public String getMenuId() { return menuId; }
    public String getPlatformId() { return platformId; }

    public static class MenuPlatformId implements Serializable {
        private String menuId;
        private String platformId;

        public MenuPlatformId() {}

        public MenuPlatformId(String menuId, String platformId) {
            this.menuId = menuId;
            this.platformId = platformId;
        }

        public String getMenuId() { return menuId; }
        public void setMenuId(String menuId) { this.menuId = menuId; }
        public String getPlatformId() { return platformId; }
        public void setPlatformId(String platformId) { this.platformId = platformId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MenuPlatformId that = (MenuPlatformId) o;
            return Objects.equals(menuId, that.menuId) && Objects.equals(platformId, that.platformId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(menuId, platformId);
        }
    }
}
