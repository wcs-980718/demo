package com.yiwei.midplat.evaluation.common;

import com.yiwei.midplat.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/overview")
class EvaluationOverviewController {

    private final EvaluationOverviewService service;

    EvaluationOverviewController(EvaluationOverviewService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<EvaluationOverviewService.Overview> overview() {
        return ApiResponse.ok(service.overview());
    }
}
