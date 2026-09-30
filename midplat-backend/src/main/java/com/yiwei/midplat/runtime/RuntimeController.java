package com.yiwei.midplat.runtime;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
class RuntimeController {

    private final RuntimeService runtimeService;

    RuntimeController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @GetMapping("/profile")
    ApiResponse<RuntimeService.ProfileView> profile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.ok(runtimeService.profile(authorization));
    }

    @PostMapping("/chat")
    ApiResponse<RuntimeService.ChatView> chat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(runtimeService.chat(authorization, request.input()));
    }

    @PostMapping("/embed")
    ApiResponse<RuntimeService.EmbedView> embed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody EmbedRequest request) {
        return ApiResponse.ok(runtimeService.embed(authorization, request.input()));
    }

    @PostMapping("/rerank")
    ApiResponse<RuntimeService.RerankView> rerank(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RerankRequest request) {
        return ApiResponse.ok(runtimeService.rerank(authorization, request.query(), request.documents()));
    }

    record ChatRequest(@NotBlank String input) {}
    record EmbedRequest(@NotBlank String input) {}
    record RerankRequest(@NotBlank String query, List<String> documents) {}
}
