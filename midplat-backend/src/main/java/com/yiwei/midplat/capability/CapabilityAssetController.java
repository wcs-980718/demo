package com.yiwei.midplat.capability;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.fusion.FusionAccess;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * 能力资产管理端点（管理域）：资产 CRUD/CAS 修订、状态流转、修订历史与项目授权。
 * 与 CapabilityController 的目录只读视图并存；正文校验与镜像同步在 CapabilityAssetService。
 */
@RestController
public class CapabilityAssetController {
    private final CapabilityAssetService service;
    public CapabilityAssetController(CapabilityAssetService service) { this.service = service; }

    @GetMapping("/api/capabilities/assets")
    ApiResponse<Map<String, Object>> list(HttpServletRequest request, @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String status, @RequestParam(required = false) String search,
                                          @RequestParam(defaultValue = "true") boolean includeCatalog,
                                          @RequestParam(defaultValue = "0") long page, @RequestParam(defaultValue = "20") long pageSize) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.page(kind, status, search, includeCatalog, page, pageSize));
    }

    @PostMapping("/api/capabilities/assets")
    ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody JsonNode body) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.create(FusionAccess.identity(request), body));
    }

    @GetMapping("/api/capabilities/assets/{id}")
    ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.detail(id));
    }

    @PutMapping("/api/capabilities/assets/{id}")
    ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable String id, @RequestBody JsonNode body) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.update(FusionAccess.identity(request), id, body));
    }

    @PatchMapping("/api/capabilities/assets/{id}/status")
    ApiResponse<Map<String, Object>> status(HttpServletRequest request, @PathVariable String id, @RequestBody JsonNode body) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.changeStatus(FusionAccess.identity(request), id, body));
    }

    @GetMapping("/api/capabilities/assets/{id}/revisions")
    ApiResponse<List<Map<String, Object>>> revisions(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.revisions(id));
    }

    @PostMapping("/api/capabilities/assets/{id}/sync")
    ApiResponse<Map<String, Object>> retrySync(HttpServletRequest request, @PathVariable String id) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.retrySync(FusionAccess.identity(request), id));
    }

    @GetMapping("/api/capabilities/projects/{projectId}/asset-grants")
    ApiResponse<Map<String, Object>> grants(HttpServletRequest request, @PathVariable String projectId,
                                            @RequestParam(defaultValue = "development") String environment) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.grantsEnvelope(projectId, environment));
    }

    @PutMapping("/api/capabilities/projects/{projectId}/asset-grants")
    ApiResponse<Map<String, Object>> replaceGrants(HttpServletRequest request, @PathVariable String projectId,
                                                   @RequestParam(defaultValue = "development") String environment, @RequestBody JsonNode body) {
        FusionAccess.admin(request);
        return ApiResponse.ok(service.replaceGrants(FusionAccess.identity(request), projectId, environment, body));
    }
}
