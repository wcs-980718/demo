package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

@RestController
@RequestMapping("/api/evaluation/runs")
class EvaluationRunController {
    private static final URI SAFE_INSTANCE = URI.create("/api/evaluation/runs");
    private final EvaluationRunService service;
    EvaluationRunController(EvaluationRunService service) { this.service = service; }

    @GetMapping
    ApiResponse<PageView<EvaluationRunService.RunSummary>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var data = service.list(PageRequest.of(page, size));
        return ApiResponse.ok(new PageView<>(data.getContent(), page, size, data.getTotalElements(), data.getTotalPages()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationRunService.RunSummary> create(@Valid @RequestBody CreateRunRequest request) {
        return ApiResponse.ok(service.create(new EvaluationRunService.CreateRunCommand(request.datasetVersionId(), request.platformId(),
                request.modelId(), request.promptId(), request.temperature(), request.maxTokens(), request.timeoutMs())));
    }

    @GetMapping("/{id}") ApiResponse<EvaluationRunService.RunSummary> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping("/{id}/results") ApiResponse<List<EvaluationRunService.ResultView>> results(@PathVariable String id) { return ApiResponse.ok(service.results(id)); }

    @PostMapping("/{id}/cancel")
    ApiResponse<EvaluationRunService.RunSummary> cancel(@PathVariable String id, @Valid @RequestBody ExpectedVersionRequest request) {
        return ApiResponse.ok(service.cancel(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/retry-errors")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationRunService.RunSummary> retry(@PathVariable String id, @Valid @RequestBody ExpectedVersionRequest request) {
        return ApiResponse.ok(service.retryErrors(id, request.expectedVersion()));
    }

    @PostMapping("/{runId}/results/{resultId}/review")
    ApiResponse<EvaluationRunService.ReviewView> review(@PathVariable String runId, @PathVariable String resultId,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(service.review(runId, resultId, request.score(), request.passed(), request.expectedVersion()));
    }

    record CreateRunRequest(@NotBlank String datasetVersionId, @NotBlank String platformId, @NotBlank String modelId,
            @NotBlank String promptId, @NotNull @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
            @NotNull @Min(1) @Max(32768) Integer maxTokens, @NotNull @Min(1) @Max(120000) Integer timeoutMs) {}
    record ExpectedVersionRequest(@NotNull Long expectedVersion) {}
    record ReviewRequest(@NotNull Long expectedVersion, @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal score, @NotNull Boolean passed) {}
    record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ignored) { return problem(HttpStatus.NOT_FOUND, "评测资源不存在"); }
    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ignored) { return problem(HttpStatus.CONFLICT, "评测状态冲突"); }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail persistenceConflict(RuntimeException ignored) { return problem(HttpStatus.CONFLICT, "评测状态冲突"); }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ignored) { return problem(HttpStatus.BAD_REQUEST, "评测请求无效"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail invalidBinding(Exception ignored) { return problem(HttpStatus.BAD_REQUEST, "评测请求无效"); }
    private ProblemDetail problem(HttpStatus status, String detail) { ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail); problem.setInstance(SAFE_INSTANCE); return problem; }
}
