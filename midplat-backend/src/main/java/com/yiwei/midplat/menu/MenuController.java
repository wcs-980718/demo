package com.yiwei.midplat.menu;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.platform.PlatformService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menus")
class MenuController {

    private final MenuService menuService;

    MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    ApiResponse<List<MenuService.MenuView>> tree() {
        return ApiResponse.ok(menuService.tree());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<MenuService.MenuView> create(@Valid @RequestBody CreateRequest request) {
        boolean visible = request.visible() == null || request.visible();
        return ApiResponse.ok(menuService.create(new MenuService.CreateMenuCmd(
                request.parentId(), request.name(), request.routeName(), request.icon(), request.platformId(), request.platformIds(), visible, request.path(), request.filePath())));
    }

    @PatchMapping("/{id}")
    ApiResponse<MenuService.MenuView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(menuService.update(id, new MenuService.UpdateMenuCmd(
                request.name(), request.routeName(), request.icon(), request.platformId(), request.platformIds(), request.visible(), request.path(), request.filePath())));
    }

    @PostMapping("/{id}/platforms")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PlatformService.PlatformView> createPlatform(
            @PathVariable String id,
            @Valid @RequestBody CreatePlatformRequest request) {
        return ApiResponse.ok(menuService.createPlatform(id, request.toCommand()));
    }

    @DeleteMapping("/{id}/platforms/{platformId}")
    ApiResponse<Void> deletePlatform(@PathVariable String id, @PathVariable String platformId) {
        menuService.deletePlatform(id, platformId);
        return ApiResponse.ok(null, "deleted");
    }

    @PostMapping("/{id}/move")
    ApiResponse<Void> move(@PathVariable String id, @RequestBody MoveRequest request) {
        menuService.move(id, request.direction());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable String id) {
        menuService.delete(id);
        return ApiResponse.ok(null, "deleted");
    }

    record CreateRequest(String parentId, @NotBlank String name, @NotBlank String routeName, String icon, String platformId, List<String> platformIds, Boolean visible, String path, String filePath) {}
    record UpdateRequest(@NotBlank String name, @NotBlank String routeName, String icon, String platformId, List<String> platformIds, boolean visible, String path, String filePath) {}
    record CreatePlatformRequest(
            @NotBlank String name,
            String entryUrl,
            String icon,
            String llmModelId,
            String embeddingModelId,
            String rerankModelId,
            String promptId) {
        PlatformService.CreatePlatformCmd toCommand() {
            return new PlatformService.CreatePlatformCmd(name, entryUrl, icon, llmModelId, embeddingModelId, rerankModelId, promptId);
        }
    }
    record MoveRequest(int direction) {}
}
