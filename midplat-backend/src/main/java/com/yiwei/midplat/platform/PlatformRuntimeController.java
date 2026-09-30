package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platforms")
class PlatformRuntimeController {

    private final PlatformRuntimeService platformRuntimeService;

    PlatformRuntimeController(PlatformRuntimeService platformRuntimeService) {
        this.platformRuntimeService = platformRuntimeService;
    }

    @GetMapping("/{id}/runtime")
    ApiResponse<RuntimeSettings> get(@PathVariable String id) {
        return ApiResponse.ok(platformRuntimeService.get(id));
    }

    @PatchMapping("/{id}/runtime")
    ApiResponse<RuntimeSettings> update(@PathVariable String id, @Valid @RequestBody RuntimeSettingsPatch request) {
        return ApiResponse.ok(platformRuntimeService.update(id, request), "已保存检索配置");
    }
}
