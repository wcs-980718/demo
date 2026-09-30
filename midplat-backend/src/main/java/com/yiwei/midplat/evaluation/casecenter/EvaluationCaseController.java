package com.yiwei.midplat.evaluation.casecenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.yiwei.midplat.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/cases")
class EvaluationCaseController {

    private static final int MAX_CASE_CONTENT_LENGTH = 20_000;

    private final EvaluationCaseService service;

    EvaluationCaseController(EvaluationCaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationCaseService.CaseView> create(
            @Valid @RequestBody CreateCaseRequest request) {
        return ApiResponse.ok(service.create(request.toCmd()));
    }

    @PostMapping("/import")
    ApiResponse<EvaluationCaseService.CaseView> importCase(
            @Valid @RequestBody ImportCaseRequest request) {
        return ApiResponse.ok(service.importCase(request.toCmd()));
    }

    @GetMapping
    ApiResponse<EvaluationCaseService.CasePage> list(
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) CaseSeverity severity,
            @RequestParam(required = false) CaseReviewStatus reviewStatus,
            @RequestParam(required = false) CaseLifecycleStatus lifecycleStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(new EvaluationCaseService.CaseFilter(
                platformId, category, severity, reviewStatus, lifecycleStatus), page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<EvaluationCaseService.CaseView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    ApiResponse<EvaluationCaseService.CaseView> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateCaseRequest request) {
        return ApiResponse.ok(service.update(id, request.toCmd()));
    }

    @PostMapping("/{id}/review")
    ApiResponse<EvaluationCaseService.CaseView> review(
            @PathVariable String id,
            @Valid @RequestBody ReviewCaseRequest request) {
        return ApiResponse.ok(service.review(id, request.decision(), request.expectedVersion()));
    }

    @PostMapping("/{id}/archive")
    ApiResponse<EvaluationCaseService.CaseView> archive(
            @PathVariable String id,
            @Valid @RequestBody ArchiveCaseRequest request) {
        return ApiResponse.ok(service.archive(id, request.expectedVersion()));
    }

    record CreateCaseRequest(
            @Size(max = 64) String platformId,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String category,
            @NotNull CaseSeverity severity,
            @NotBlank @Size(max = MAX_CASE_CONTENT_LENGTH) String inputText,
            @NotNull JsonNode expected,
            @NotNull EvaluatorType evaluatorType) {

        EvaluationCaseService.CreateCaseCmd toCmd() {
            return new EvaluationCaseService.CreateCaseCmd(
                    platformId,
                    name,
                    category,
                    severity,
                    inputText,
                    expected,
                    evaluatorType);
        }

        @Override public String toString() {
            return "CreateCaseRequest[name=" + name + ", inputText=[redacted], expected=[redacted]]";
        }
    }

    record UpdateCaseRequest(
            @NotNull Long expectedVersion,
            @Size(max = 64) String platformId,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String category,
            @NotNull CaseSeverity severity,
            @NotBlank @Size(max = MAX_CASE_CONTENT_LENGTH) String inputText,
            @NotNull JsonNode expected,
            @NotNull EvaluatorType evaluatorType) {

        EvaluationCaseService.UpdateCaseCmd toCmd() {
            return new EvaluationCaseService.UpdateCaseCmd(
                    expectedVersion,
                    platformId,
                    name,
                    category,
                    severity,
                    inputText,
                    expected,
                    evaluatorType);
        }

        @Override public String toString() {
            return "UpdateCaseRequest[name=" + name + ", inputText=[redacted], expected=[redacted]]";
        }
    }

    record ImportCaseRequest(
            @Size(max = 64) String platformId,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String category,
            @NotNull CaseSeverity severity,
            @NotNull CaseSourceType sourceType,
            @NotBlank @Size(max = 256) String sourceRef,
            @NotBlank @Size(max = MAX_CASE_CONTENT_LENGTH) String inputText,
            @NotNull JsonNode expected,
            @NotNull EvaluatorType evaluatorType) {

        EvaluationCaseService.ImportCaseCmd toCmd() {
            return new EvaluationCaseService.ImportCaseCmd(
                    platformId,
                    name,
                    category,
                    severity,
                    sourceType,
                    sourceRef,
                    inputText,
                    expected,
                    evaluatorType);
        }

        @Override public String toString() {
            return "ImportCaseRequest[name=" + name + ", inputText=[redacted], expected=[redacted]]";
        }
    }

    record ReviewCaseRequest(
            @NotNull ReviewDecision decision,
            @NotNull Long expectedVersion) {}

    record ArchiveCaseRequest(@NotNull Long expectedVersion) {}
}
