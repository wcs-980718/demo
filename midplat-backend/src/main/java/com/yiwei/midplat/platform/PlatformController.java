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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platforms")
class PlatformController {

    private final PlatformService platformService;

    PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    @GetMapping
    ApiResponse<List<PlatformService.PlatformView>> list() {
        return ApiResponse.ok(platformService.list());
    }

    @GetMapping("/{id}")
    ApiResponse<PlatformService.PlatformView> get(@PathVariable String id) {
        return ApiResponse.ok(platformService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PlatformService.PlatformView> create(@Valid @RequestBody PlatformRequest request) {
        return ApiResponse.ok(platformService.create(request.toCreate()));
    }

    @PatchMapping("/{id}")
    ApiResponse<PlatformService.PlatformView> update(@PathVariable String id, @Valid @RequestBody PlatformRequest request) {
        return ApiResponse.ok(platformService.update(id, request.toUpdate()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable String id) {
        platformService.delete(id);
        return ApiResponse.ok(null, "deleted");
    }

    record PlatformRequest(
            @NotBlank String name,
            String entryUrl,
            String icon,
            String llmModelId,
            String embeddingModelId,
            String rerankModelId,
            String promptId) {
        PlatformService.CreatePlatformCmd toCreate() {
            return new PlatformService.CreatePlatformCmd(name, entryUrl, icon, llmModelId, embeddingModelId, rerankModelId, promptId);
        }

        PlatformService.UpdatePlatformCmd toUpdate() {
            return new PlatformService.UpdatePlatformCmd(name, entryUrl, icon, llmModelId, embeddingModelId, rerankModelId, promptId);
        }
    }
}
