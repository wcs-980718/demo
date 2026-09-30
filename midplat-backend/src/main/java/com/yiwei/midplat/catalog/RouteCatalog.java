package com.yiwei.midplat.catalog;

import java.util.Arrays;
import java.util.List;

public enum RouteCatalog {
    HOME("home", "/home", "首页", "src/pages/home/index.tsx"),
    ENTRY("entry", "/entry", "平台分类", "src/pages/entry/index.tsx"),
    ENTRY_SCENE("entry-scene", "/entry/scene/:sceneId", "场景入口", "src/pages/entry/scene.tsx"),
    ENTRY_CATEGORY("entry-category", "/entry/category/:menuId", "分类平台", "src/pages/entry/category.tsx"),
    EVALUATION("evaluation", "/evaluation", "异常总览", "src/pages/evaluation/index.tsx"),
    CAPABILITIES("capabilities", "/capabilities", "对外接口总览", "src/pages/capabilities/index.tsx"),
    AGENT_PLATFORM("agent-platform", "/agent-platform", "智能体开发", "src/pages/agent-platform/index.tsx"),
    MODELS("models", "/models", "模型管理", "src/pages/models/index.tsx"),
    PROMPTS("prompts", "/prompts", "提示词管理", "src/pages/prompts/index.tsx"),
    MENUS("menus", "/settings/menus", "菜单设置", "src/pages/settings/menus/index.tsx");

    private final String name;
    private final String path;
    private final String title;
    private final String filePath;

    RouteCatalog(String name, String path, String title, String filePath) {
        this.name = name;
        this.path = path;
        this.title = title;
        this.filePath = filePath;
    }

    public String routeName() {
        return name;
    }

    public String path() {
        return path;
    }

    public String title() {
        return title;
    }

    public String filePath() {
        return filePath;
    }

    public static RouteCatalog require(String name) {
        return Arrays.stream(values())
                .filter(item -> item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知路由 name：" + name));
    }

    public static java.util.Optional<RouteCatalog> findOptional(String name) {
        return Arrays.stream(values())
                .filter(item -> item.name.equals(name))
                .findFirst();
    }

    public static boolean isStandard(String name) {
        return findOptional(name).isPresent();
    }

    public static List<RouteView> list() {
        return Arrays.stream(values()).map(item -> new RouteView(item.name, item.path, item.title, item.filePath)).toList();
    }

    public record RouteView(String name, String path, String title, String filePath) {}
}
