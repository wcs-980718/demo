package com.yiwei.midplat.menu;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "midplat_menu")
public class MenuItem extends BaseEntity {

    @Column(name = "parent_id", length = 64)
    private String parentId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "route_name", nullable = false, length = 64)
    private String routeName;

    @Column(name = "icon", nullable = false, length = 64)
    private String icon;

    @Column(name = "platform_id", length = 64)
    private String platformId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "visible", nullable = false)
    private boolean visible;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "path", length = 128)
    private String path;

    @Column(name = "file_path", length = 256)
    private String filePath;

    protected MenuItem() {}

    public MenuItem(String id, String parentId, String name, String routeName, String icon, String platformId, int sortOrder, boolean visible, boolean locked, String path, String filePath) {
        super(id);
        this.parentId = parentId;
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.routeName = DomainAssertions.requireText(routeName, "routeName cannot be blank");
        this.icon = icon == null || icon.isBlank() ? "LayoutDashboard" : icon.trim();
        this.platformId = platformId;
        this.sortOrder = sortOrder;
        this.visible = visible;
        this.locked = locked;
        this.path = path;
        this.filePath = filePath;
    }

    public void update(String name, String routeName, String icon, String platformId, boolean visible, String path, String filePath) {
        if (locked && !"menus".equals(routeName) && this.routeName.equals("menus") && parentId != null) {
            // allow name/icon/visible on locked items except changing away from system routes
        }
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        if (!locked) {
            this.routeName = DomainAssertions.requireText(routeName, "routeName cannot be blank");
            this.platformId = platformId;
            this.path = path;
            this.filePath = filePath;
        }
        if (icon != null && !icon.isBlank()) {
            this.icon = icon.trim();
        }
        this.visible = visible;
    }

    public void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getParentId() { return parentId; }
    public String getName() { return name; }
    public String getRouteName() { return routeName; }
    public String getIcon() { return icon; }
    public String getPlatformId() { return platformId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isVisible() { return visible; }
    public boolean isLocked() { return locked; }
    public String getPath() { return path; }
    public String getFilePath() { return filePath; }
}
