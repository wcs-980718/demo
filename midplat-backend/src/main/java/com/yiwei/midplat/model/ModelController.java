package com.yiwei.midplat.model;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
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
@RequestMapping("/api/models")
class ModelController {

    private final ModelService modelService;

    ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    ApiResponse<List<ModelService.ModelView>> list() {
        return ApiResponse.ok(modelService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ModelService.ModelView> create(@Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(modelService.create(request.toCmd()));
    }

    @PatchMapping("/{id}")
    ApiResponse<ModelService.ModelView> update(@PathVariable String id, @Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(modelService.update(id, request.toCmd()));
    }

    @PostMapping("/{id}/ping")
    ApiResponse<ModelService.ModelView> ping(@PathVariable String id) {
        return ApiResponse.ok(modelService.ping(id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable String id) {
        modelService.delete(id);
        return ApiResponse.ok(null, "deleted");
    }

    record ModelRequest(
            @NotBlank String name,
            @NotNull ModelKind kind,
            @NotBlank String model,
            @NotBlank String baseUrl,
            String apiKey,
            @DecimalMin(value = "0", inclusive = true) @Digits(integer = 12, fraction = 8) BigDecimal inputPricePerMillion,
            @DecimalMin(value = "0", inclusive = true) @Digits(integer = 12, fraction = 8) BigDecimal outputPricePerMillion, ModelService.ModelCapabilities capabilities) {
        ModelService.CreateModelCmd toCmd() {
            return new ModelService.CreateModelCmd(name, kind, model, baseUrl, apiKey,
                    inputPricePerMillion, outputPricePerMillion, capabilities);
        }
    }
}
