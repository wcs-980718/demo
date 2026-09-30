package com.yiwei.midplat.menu;

import com.yiwei.midplat.catalog.RouteCatalog;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.platform.PlatformService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

    public static final String ENTRY_MENU_ID = "menu-entry";

    private final MenuItemRepository repository;
    private final MenuPlatformRepository menuPlatformRepository;
    private final PlatformService platformService;

    MenuService(MenuItemRepository repository, MenuPlatformRepository menuPlatformRepository, PlatformService platformService) {
        this.repository = repository;
        this.menuPlatformRepository = menuPlatformRepository;
        this.platformService = platformService;
    }

    @Transactional(readOnly = true)
    public List<MenuView> tree() {
        var all = repository.findAllByOrderBySortOrderAscNameAsc();
        Map<String, List<MenuItem>> byParent = all.stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(MenuItem::getParentId));
        Map<String, List<String>> platformIdsByMenu = loadPlatformIdsByMenu(all.stream().map(MenuItem::getId).toList());
        return all.stream()
                .filter(item -> item.getParentId() == null)
                .sorted(Comparator.comparingInt(MenuItem::getSortOrder).thenComparing(MenuItem::getName))
                .map(item -> toView(item, byParent, platformIdsByMenu))
                .toList();
    }

    @Transactional
    public MenuView create(CreateMenuCmd cmd) {
        validateCustomRoute(cmd.routeName(), cmd.path(), cmd.filePath());
        List<String> platformIds = normalizePlatformIds(cmd.parentId(), cmd.platformIds(), cmd.platformId(), null);
        int order = nextOrder(cmd.parentId());
        var entity = new MenuItem(Identities.newId(), blankToNull(cmd.parentId()), cmd.name(), cmd.routeName(), cmd.icon(), platformIds.isEmpty() ? null : platformIds.get(0), order, cmd.visible(), false, blankToNull(cmd.path()), blankToNull(cmd.filePath()));
        repository.save(entity);
        syncPlatformBindings(entity.getId(), platformIds);
        Map<String, List<MenuItem>> byParent = Map.of();
        return toView(entity, byParent, Map.of(entity.getId(), platformIds));
    }

    @Transactional
    public PlatformService.PlatformView createPlatform(String menuId, PlatformService.CreatePlatformCmd cmd) {
        var menu = require(menuId);
        if (!ENTRY_MENU_ID.equals(menu.getParentId())) {
            throw new IllegalArgumentException("只能在 AI 工作台分类中登记项目");
        }
        var platform = platformService.create(cmd);
        var platformIds = new ArrayList<>(loadPlatformIds(menuId));
        platformIds.add(platform.id());
        var normalized = normalizePlatformIds(menu.getParentId(), platformIds, null, menuId);
        menu.update(menu.getName(), menu.getRouteName(), menu.getIcon(), normalized.get(0), menu.isVisible(), menu.getPath(), menu.getFilePath());
        syncPlatformBindings(menuId, normalized);
        return platform;
    }

    @Transactional
    public void deletePlatform(String menuId, String platformId) {
        var menu = require(menuId);
        if (!ENTRY_MENU_ID.equals(menu.getParentId())) {
            throw new IllegalArgumentException("只能删除 AI 工作台分类中的项目");
        }
        var platformIds = new ArrayList<>(loadPlatformIds(menuId));
        if (!platformIds.remove(platformId)) {
            throw new ResourceNotFoundException("platform is not bound to menu: " + platformId);
        }
        menu.update(
                menu.getName(),
                menu.getRouteName(),
                menu.getIcon(),
                platformIds.isEmpty() ? null : platformIds.get(0),
                menu.isVisible(),
                menu.getPath(),
                menu.getFilePath());
        syncPlatformBindings(menuId, platformIds);
        platformService.delete(platformId);
    }

    @Transactional
    public MenuView update(String id, UpdateMenuCmd cmd) {
        var entity = require(id);
        validateCustomRoute(cmd.routeName(), cmd.path(), cmd.filePath());
        List<String> platformIds = entity.isLocked() ? loadPlatformIds(id) : normalizePlatformIds(entity.getParentId(), cmd.platformIds(), cmd.platformId(), id);
        entity.update(cmd.name(), cmd.routeName(), cmd.icon(), platformIds.isEmpty() ? null : platformIds.get(0), cmd.visible(), blankToNull(cmd.path()), blankToNull(cmd.filePath()));
        if (!entity.isLocked()) {
            syncPlatformBindings(id, platformIds);
        }
        var all = repository.findAllByOrderBySortOrderAscNameAsc();
        Map<String, List<MenuItem>> byParent = all.stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(MenuItem::getParentId));
        Map<String, List<String>> platformIdsByMenu = loadPlatformIdsByMenu(all.stream().map(MenuItem::getId).toList());
        return toView(entity, byParent, platformIdsByMenu);
    }

    @Transactional
    public void delete(String id) {
        var entity = require(id);
        if (entity.isLocked()) {
            throw new IllegalArgumentException("系统锁定菜单不能删除");
        }
        var children = repository.findByParentIdOrderBySortOrderAscNameAsc(id);
        if (!children.isEmpty()) {
            throw new IllegalArgumentException("请先删除子菜单");
        }
        menuPlatformRepository.deleteByMenuId(id);
        repository.delete(entity);
    }

    @Transactional
    public void move(String id, int direction) {
        var entity = require(id);
        var siblings = entity.getParentId() == null
                ? repository.findAllByOrderBySortOrderAscNameAsc().stream().filter(item -> item.getParentId() == null).toList()
                : repository.findByParentIdOrderBySortOrderAscNameAsc(entity.getParentId());
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                index = i;
                break;
            }
        }
        int target = index + direction;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        var a = siblings.get(index);
        var b = siblings.get(target);
        int tmp = a.getSortOrder();
        a.moveTo(b.getSortOrder());
        b.moveTo(tmp);
    }

    private List<String> normalizePlatformIds(String parentId, List<String> platformIds, String legacyPlatformId, String currentMenuId) {
        List<String> ids = platformIds;
        if ((ids == null || ids.isEmpty()) && legacyPlatformId != null && !legacyPlatformId.isBlank()) {
            ids = List.of(legacyPlatformId.trim());
        }
        if (ids == null) ids = List.of();
        ids = ids.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (!ENTRY_MENU_ID.equals(parentId)) {
            if (!ids.isEmpty()) {
                throw new IllegalArgumentException("只有「平台入口」下的子菜单可以绑定平台");
            }
            return List.of();
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        for (String pid : ids) {
            platformService.get(pid);
        }
        List<String> occupied = findOccupiedPlatforms(ids, currentMenuId);
        if (!occupied.isEmpty()) {
            throw new IllegalArgumentException("平台已被其他分类绑定: " + String.join(", ", occupied));
        }
        return ids;
    }

    private List<String> findOccupiedPlatforms(List<String> ids, String currentMenuId) {
        List<String> occupied = new ArrayList<>();
        for (String pid : ids) {
            boolean exists = currentMenuId == null
                    ? !menuPlatformRepository.findByPlatformIdIn(List.of(pid)).isEmpty()
                    : menuPlatformRepository.existsByPlatformIdAndMenuIdNot(pid, currentMenuId);
            if (exists) {
                var holders = menuPlatformRepository.findByPlatformIdIn(List.of(pid));
                String holderMenu = holders.isEmpty() ? pid : holders.get(0).getMenuId();
                occupied.add(pid + "(已被" + holderMenu + "绑定)");
            } else {
                var legacy = repository.findAllByOrderBySortOrderAscNameAsc().stream()
                        .filter(m -> !m.getId().equals(currentMenuId) && pid.equals(m.getPlatformId()))
                        .findFirst();
                if (legacy.isPresent()) {
                    occupied.add(pid + "(已被" + legacy.get().getName() + "绑定)");
                }
            }
        }
        return occupied;
    }

    private void syncPlatformBindings(String menuId, List<String> platformIds) {
        menuPlatformRepository.deleteByMenuId(menuId);
        for (String pid : platformIds) {
            menuPlatformRepository.save(new MenuPlatform(menuId, pid));
        }
    }

    private Map<String, List<String>> loadPlatformIdsByMenu(List<String> menuIds) {
        if (menuIds.isEmpty()) return Map.of();
        var all = menuPlatformRepository.findByPlatformIdIn(
                repository.findAllByOrderBySortOrderAscNameAsc().stream()
                        .flatMap(m -> menuPlatformRepository.findByMenuId(m.getId()).stream().map(MenuPlatform::getPlatformId))
                        .distinct().toList()
        );
        Map<String, List<String>> map = new java.util.HashMap<>();
        for (String mid : menuIds) {
            var list = menuPlatformRepository.findByMenuId(mid).stream().map(MenuPlatform::getPlatformId).toList();
            if (!list.isEmpty()) {
                map.put(mid, list);
            } else {
                var legacy = repository.findById(mid).map(MenuItem::getPlatformId).orElse(null);
                if (legacy != null && !legacy.isBlank()) {
                    map.put(mid, List.of(legacy));
                }
            }
        }
        return map;
    }

    private List<String> loadPlatformIds(String menuId) {
        var list = menuPlatformRepository.findByMenuId(menuId).stream().map(MenuPlatform::getPlatformId).toList();
        if (!list.isEmpty()) return list;
        var legacy = repository.findById(menuId).map(MenuItem::getPlatformId).orElse(null);
        return legacy == null || legacy.isBlank() ? List.of() : List.of(legacy);
    }

    private int nextOrder(String parentId) {
        var siblings = parentId == null || parentId.isBlank()
                ? repository.findAllByOrderBySortOrderAscNameAsc().stream().filter(item -> item.getParentId() == null).toList()
                : repository.findByParentIdOrderBySortOrderAscNameAsc(parentId);
        return siblings.stream().mapToInt(MenuItem::getSortOrder).max().orElse(0) + 10;
    }

    private MenuItem require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("menu not found: " + id));
    }

    private MenuView toView(MenuItem item, Map<String, List<MenuItem>> byParent, Map<String, List<String>> platformIdsByMenu) {
        var children = new ArrayList<>(byParent.getOrDefault(item.getId(), List.of()));
        children.sort(Comparator.comparingInt(MenuItem::getSortOrder).thenComparing(MenuItem::getName));
        String path = item.getPath();
        if (path == null || path.isBlank()) {
            var catalog = RouteCatalog.findOptional(item.getRouteName());
            path = catalog.map(RouteCatalog::path).orElse(null);
        }
        String filePath = item.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            var catalog = RouteCatalog.findOptional(item.getRouteName());
            filePath = catalog.map(RouteCatalog::filePath).orElse(null);
        }
        List<String> pids = new ArrayList<>(platformIdsByMenu.getOrDefault(item.getId(), loadPlatformIds(item.getId())));
        String platformId = item.getPlatformId();
        if (platformId != null && !platformId.isBlank() && pids.remove(platformId)) {
            pids.add(0, platformId);
        }
        if ((platformId == null || platformId.isBlank()) && !pids.isEmpty()) {
            platformId = pids.get(0);
        }
        return new MenuView(
                item.getId(),
                item.getParentId(),
                item.getName(),
                item.getRouteName(),
                path,
                filePath,
                item.getIcon(),
                platformId,
                pids,
                item.getSortOrder(),
                item.isVisible(),
                item.isLocked(),
                children.stream().map(child -> toView(child, byParent, platformIdsByMenu)).toList());
    }

    private MenuView toView(MenuItem item, Map<String, List<MenuItem>> byParent) {
        return toView(item, byParent, loadPlatformIdsByMenu(List.of(item.getId())));
    }

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("^src/pages/.+\\.tsx?$");

    private static void validateCustomRoute(String routeName, String path, String filePath) {
        if (RouteCatalog.isStandard(routeName)) {
            return;
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("自定义路由必须提供 path");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("自定义路由必须提供 filePath");
        }
        if (!FILE_PATH_PATTERN.matcher(filePath).matches()) {
            throw new IllegalArgumentException("filePath 必须符合 src/pages/... 格式，且以 .ts 或 .tsx 结尾");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CreateMenuCmd(String parentId, String name, String routeName, String icon, String platformId, List<String> platformIds, boolean visible, String path, String filePath) {
        public CreateMenuCmd(String parentId, String name, String routeName, String icon, String platformId, boolean visible, String path, String filePath) {
            this(parentId, name, routeName, icon, platformId, platformId == null ? List.of() : List.of(platformId), visible, path, filePath);
        }
    }
    public record UpdateMenuCmd(String name, String routeName, String icon, String platformId, List<String> platformIds, boolean visible, String path, String filePath) {
        public UpdateMenuCmd(String name, String routeName, String icon, String platformId, boolean visible, String path, String filePath) {
            this(name, routeName, icon, platformId, platformId == null ? List.of() : List.of(platformId), visible, path, filePath);
        }
    }
    public record MenuView(String id, String parentId, String name, String routeName, String path, String filePath, String icon, String platformId, List<String> platformIds, int sortOrder, boolean visible, boolean locked, List<MenuView> children) {}
}
