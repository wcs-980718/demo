package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platforms")
class RootCauseAgentPromptController {

    private final RootCauseAgentPromptService service;

    RootCauseAgentPromptController(RootCauseAgentPromptService service) {
        this.service = service;
    }

    @GetMapping("/{id}/agent-prompt")
    ApiResponse<AgentPromptBundle> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/agent-runtime")
    ApiResponse<AgentRuntimeView> runtime(@PathVariable String id) {
        return ApiResponse.ok(service.runtime(id));
    }

    @PutMapping("/{id}/agent-prompt")
    ApiResponse<AgentPromptBundle> update(@PathVariable String id, @Valid @RequestBody AgentPromptUpdate request) {
        return ApiResponse.ok(service.update(id, request), "智能体提示词已保存并热更新");
    }
}

record AgentPromptBundle(
        String platformId,
        String agent,
        String task,
        String soulContent,
        String skillKey,
        String skillTitle,
        String skillContent,
        boolean writable) {}

record AgentRuntimeView(
        String platformId,
        String status,
        String agent,
        String model,
        int skills,
        String task,
        String skillKey,
        String skillTitle,
        String runtimeUrl) {}

record AgentPromptUpdate(
        @NotBlank(message = "SOUL 不能为空") String soulContent,
        @NotBlank(message = "任务技能提示词不能为空") String skillContent) {}
