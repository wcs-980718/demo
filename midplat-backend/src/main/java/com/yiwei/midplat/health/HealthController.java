package com.yiwei.midplat.health;

import com.yiwei.midplat.common.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
class HealthController {

    @GetMapping
    ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("UP", Instant.now()));
    }

    record HealthResponse(String status, Instant checkedAt) {}
}
