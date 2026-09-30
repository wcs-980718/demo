package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目页读取“接入的智能体配置”与热更新下发情况；模型和提示词本身只在智能体开发里修改。 */
@RestController
@RequestMapping("/api/platforms/{id}/agent-config")
class PlatformAgentConfigController {
    private final AgentConfigDeliveryService service;

    PlatformAgentConfigController(AgentConfigDeliveryService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<Map<String, Object>> view(@PathVariable String id) {
        return ApiResponse.ok(service.view(id));
    }

    @PostMapping("/sync")
    ApiResponse<Map<String, Object>> sync(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(service.syncAndDispatch(id, force));
    }
}
