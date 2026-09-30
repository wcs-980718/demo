package com.yiwei.midplat.prompt;

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
@RequestMapping("/api/prompts")
class PromptController {

    private final PromptService promptService;

    PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping
    ApiResponse<List<PromptService.PromptView>> list() {
        return ApiResponse.ok(promptService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PromptService.PromptView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(promptService.create(new PromptService.CreatePromptCmd(request.name(), request.slot(), request.version(), request.body())));
    }

    @PatchMapping("/{id}")
    ApiResponse<PromptService.PromptView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(promptService.update(id, new PromptService.UpdatePromptCmd(request.name(), request.version(), request.body())));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable String id) {
        promptService.delete(id);
        return ApiResponse.ok(null, "deleted");
    }

    record CreateRequest(@NotBlank String name, @NotBlank String slot, String version, @NotBlank String body) {
        @Override public String toString() { return "CreateRequest[name=" + name + ", slot=" + slot + ", version=" + version + ", body=[redacted]]"; }
    }
    record UpdateRequest(@NotBlank String name, String version, @NotBlank String body) {
        @Override public String toString() { return "UpdateRequest[name=" + name + ", version=" + version + ", body=[redacted]]"; }
    }
}
