package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platforms")
class PlatformDeliveryController {
    private final PlatformConfigDelivery delivery;
    private final ManagedPlatformRepository platforms;
    PlatformDeliveryController(PlatformConfigDelivery delivery, ManagedPlatformRepository platforms) {
        this.delivery=delivery; this.platforms=platforms;
    }
    @GetMapping("/{id}/delivery")
    ApiResponse<Map<String,Object>> status(@PathVariable String id) {
        if (!platforms.existsById(id)) throw new ResourceNotFoundException("项目不存在");
        return ApiResponse.ok(delivery.status(id));
    }
}
