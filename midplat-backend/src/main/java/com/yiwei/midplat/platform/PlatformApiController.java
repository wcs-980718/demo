package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platforms")
class PlatformApiController {

    private final PlatformApiService platformApiService;

    PlatformApiController(PlatformApiService platformApiService) {
        this.platformApiService = platformApiService;
    }

    @GetMapping("/{id}/apis")
    ApiResponse<List<PlatformApiItem>> list(@PathVariable String id) {
        return ApiResponse.ok(platformApiService.list(id));
    }

    @PostMapping("/{id}/apis")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<List<PlatformApiItem>> create(@PathVariable String id, @Valid @RequestBody ApiWriteRequest request) {
        return ApiResponse.ok(platformApiService.create(id, request.toCmd()));
    }

    @PutMapping("/{id}/apis/{apiId}")
    ApiResponse<List<PlatformApiItem>> update(
            @PathVariable String id, @PathVariable String apiId, @Valid @RequestBody ApiWriteRequest request) {
        return ApiResponse.ok(platformApiService.update(id, apiId, request.toCmd()));
    }

    @PatchMapping("/{id}/apis/{apiId}")
    ApiResponse<List<PlatformApiItem>> toggle(
            @PathVariable String id, @PathVariable String apiId, @RequestBody ToggleRequest request) {
        return ApiResponse.ok(platformApiService.toggle(id, apiId, request.external()));
    }

    @DeleteMapping("/{id}/apis/{apiId}")
    ApiResponse<List<PlatformApiItem>> delete(@PathVariable String id, @PathVariable String apiId) {
        return ApiResponse.ok(platformApiService.delete(id, apiId));
    }

    record ToggleRequest(boolean external) {}

    record ApiWriteRequest(
            String id,
            @NotBlank String name,
            @NotBlank String method,
            @NotBlank String path,
            String note,
            boolean external) {
        PlatformApiService.ApiWriteCmd toCmd() {
            return new PlatformApiService.ApiWriteCmd(id, name, method, path, note, external);
        }
    }
}
