package com.yiwei.midplat.evaluation.gate;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.evaluation.experiment.EvaluationExperimentService;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestController
@RequestMapping("/api/evaluation")
@Validated
class EvaluationGateController {

    private static final URI SAFE_INSTANCE = URI.create("/api/evaluation/gates");
    private final EvaluationGateService service;

    EvaluationGateController(EvaluationGateService service) { this.service = service; }

    @GetMapping("/gates")
    ApiResponse<List<EvaluationGateService.PolicyView>> listPolicies() {
        return ApiResponse.ok(service.listPolicies());
    }

    @PostMapping("/gates")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationGateService.PolicyView> create(@Valid @RequestBody PolicyRequest request) {
        return ApiResponse.ok(service.create(request.toCreateCommand()));
    }

    @PatchMapping("/gates/{id}")
    ApiResponse<EvaluationGateService.PolicyView> revise(
            @PathVariable @NotBlank @Size(max = 64) String id,
            @Valid @RequestBody UpdatePolicyRequest request) {
        return ApiResponse.ok(service.revise(id, request.toUpdateCommand()));
    }

    @GetMapping("/gate-decisions")
    ApiResponse<List<EvaluationGateService.DecisionView>> listDecisions(
            @RequestParam @NotBlank @Size(max = 64) String runId) {
        return ApiResponse.ok(service.listDecisions(runId));
    }

    @PostMapping("/runs/{id}/gate-decision")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvaluationGateService.DecisionView> decide(
            @PathVariable @NotBlank @Size(max = 64) String id,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.decide(id,
                new EvaluationGateService.DecisionCommand(request.baselineRunId, request.policyId)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ignored) { return problem(HttpStatus.NOT_FOUND, "评测门禁资源不存在"); }
    @ExceptionHandler({ConflictException.class, ObjectOptimisticLockingFailureException.class,
            DataIntegrityViolationException.class, EvaluationExperimentService.SnapshotConflictException.class,
            EvaluationExperimentService.AlignmentConflictException.class})
    ProblemDetail conflict(RuntimeException ignored) { return problem(HttpStatus.CONFLICT, "评测门禁状态冲突"); }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(RuntimeException ignored) { return problem(HttpStatus.BAD_REQUEST, "评测门禁请求无效"); }
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail internal(RuntimeException ignored) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "评测门禁处理失败");
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            ConstraintViolationException.class})
    ProblemDetail invalidBinding(Exception ignored) { return problem(HttpStatus.BAD_REQUEST, "评测门禁请求无效"); }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(SAFE_INSTANCE);
        return problem;
    }

    static class PolicyRequest {
        @JsonProperty @NotBlank @Size(max = 128) String name;
        @JsonProperty @Size(max = 64) @Pattern(regexp = "(?s).*\\S.*") String platformId;
        @JsonProperty @Size(max = 64) @Pattern(regexp = "(?s).*\\S.*") String category;
        @JsonProperty
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
        BigDecimal minPassRate;
        @JsonProperty
        @NotNull @DecimalMin("0.00000000") @DecimalMax("999999999999.99999999")
        @Digits(integer = 12, fraction = 8) BigDecimal maxCostGrowthPercent;
        @JsonProperty @NotNull @PositiveOrZero Long maxAverageLatencyMs;
        @JsonProperty @NotNull @PositiveOrZero Long maxP95LatencyMs;
        @JsonProperty @NotNull Boolean requireCriticalCasesPassed;
        @JsonProperty @NotNull Boolean enabled;

        @JsonAnySetter
        void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("门禁请求包含未知字段");
        }

        EvaluationGateService.CreatePolicyCommand toCreateCommand() {
            return new EvaluationGateService.CreatePolicyCommand(name, platformId, category, minPassRate,
                    maxCostGrowthPercent, maxAverageLatencyMs, maxP95LatencyMs,
                    requireCriticalCasesPassed, enabled);
        }
    }

    static final class UpdatePolicyRequest extends PolicyRequest {
        @JsonProperty @NotNull @PositiveOrZero Long expectedVersion;

        EvaluationGateService.UpdatePolicyCommand toUpdateCommand() {
            return new EvaluationGateService.UpdatePolicyCommand(expectedVersion, name, platformId, category,
                    minPassRate, maxCostGrowthPercent, maxAverageLatencyMs, maxP95LatencyMs,
                    requireCriticalCasesPassed, enabled);
        }
    }

    static final class DecisionRequest {
        @JsonProperty @NotBlank @Size(max = 64) String baselineRunId;
        @JsonProperty @NotBlank @Size(max = 64) String policyId;

        @JsonAnySetter
        void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("门禁请求包含未知字段");
        }
    }
}
