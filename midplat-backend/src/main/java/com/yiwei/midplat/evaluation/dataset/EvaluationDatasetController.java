package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
class EvaluationDatasetController {

    private static final int MAX_DATASET_NAME_LENGTH = 128;
    private static final int MAX_DATASET_DESCRIPTION_LENGTH = 1_000;
    private static final int MAX_DATASET_ITEMS = 1_000;

    private final EvaluationDatasetService service;

    EvaluationDatasetController(EvaluationDatasetService service) {
        this.service = service;
    }

    @GetMapping("/datasets")
    ApiResponse<List<EvaluationDatasetService.DatasetSummaryView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/datasets/{id}")
    ApiResponse<EvaluationDatasetService.DatasetView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationDatasetService.DatasetView> create(
            @Valid @RequestBody CreateDatasetRequest request) {
        return ApiResponse.ok(service.create(new EvaluationDatasetService.CreateDatasetCmd(
                request.name(), request.description())));
    }

    @PostMapping("/datasets/{id}/versions")
    ApiResponse<EvaluationDatasetService.DatasetVersionView> deriveDraft(
            @PathVariable String id,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ApiResponse.ok(service.deriveDraft(id, request.expectedVersion()));
    }

    @PutMapping("/dataset-versions/{id}/items")
    ApiResponse<EvaluationDatasetService.DatasetVersionView> replaceItems(
            @PathVariable String id,
            @Valid @RequestBody ReplaceDatasetItemsRequest request) {
        return ApiResponse.ok(service.replaceItems(id, request.caseIds(), request.expectedVersion()));
    }

    @PostMapping("/dataset-versions/{id}/freeze")
    ApiResponse<EvaluationDatasetService.DatasetVersionView> freeze(
            @PathVariable String id,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ApiResponse.ok(service.freeze(id, request.expectedVersion()));
    }

    record CreateDatasetRequest(
            @NotBlank @Size(max = MAX_DATASET_NAME_LENGTH) String name,
            @Size(max = MAX_DATASET_DESCRIPTION_LENGTH) String description) {}

    record ExpectedVersionRequest(@NotNull Long expectedVersion) {}

    record ReplaceDatasetItemsRequest(
            @NotNull Long expectedVersion,
            @NotNull @Size(max = MAX_DATASET_ITEMS) List<@NotBlank String> caseIds) {}
}
