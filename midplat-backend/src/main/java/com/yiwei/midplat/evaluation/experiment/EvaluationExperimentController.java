package com.yiwei.midplat.evaluation.experiment;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import jakarta.validation.ConstraintViolationException;

@RestController
@RequestMapping("/api/evaluation/experiments")
@Validated
class EvaluationExperimentController {
    private static final URI SAFE_INSTANCE = URI.create("/api/evaluation/experiments/compare");
    private final EvaluationExperimentService service;

    EvaluationExperimentController(EvaluationExperimentService service) { this.service = service; }

    @GetMapping("/compare")
    ApiResponse<EvaluationExperimentService.Comparison> compare(@RequestParam @NotBlank @Size(max = 64) String baselineRunId,
            @RequestParam @NotBlank @Size(max = 64) String candidateRunId) {
        return ApiResponse.ok(service.compare(baselineRunId, candidateRunId));
    }

    @ExceptionHandler(EvaluationExperimentService.SnapshotConflictException.class)
    ProblemDetail snapshotConflict(EvaluationExperimentService.SnapshotConflictException ignored) { return problem(HttpStatus.CONFLICT, "评测快照不一致"); }
    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ignored) { return problem(HttpStatus.NOT_FOUND, "评测运行不存在"); }
    @ExceptionHandler(EvaluationExperimentService.AlignmentConflictException.class)
    ProblemDetail alignmentConflict(EvaluationExperimentService.AlignmentConflictException ignored) { return problem(HttpStatus.CONFLICT, "评测结果快照不一致"); }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(RuntimeException ignored) { return problem(HttpStatus.BAD_REQUEST, "实验比较请求无效"); }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missing(MissingServletRequestParameterException ignored) { return problem(HttpStatus.BAD_REQUEST, "实验比较请求无效"); }
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidBinding(HandlerMethodValidationException ignored) { return problem(HttpStatus.BAD_REQUEST, "实验比较请求无效"); }
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidConstraint(ConstraintViolationException ignored) { return problem(HttpStatus.BAD_REQUEST, "实验比较请求无效"); }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(SAFE_INSTANCE);
        return problem;
    }
}
