package com.yiwei.midplat.common.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.env.Environment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI SAFE_ERROR_INSTANCE = URI.create("/api");
    private final Environment environment;

    GlobalExceptionHandler(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = ex.getAllErrors().stream().map(DefaultMessageSourceResolvable::getDefaultMessage).toList();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation Error");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", errors);
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of(ex.getMessage()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorized");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of(ex.getMessage()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail handleForbidden(ForbiddenException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of(ex.getMessage()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        if (ex.getSafeInstance() != null) {
            problem.setInstance(ex.getSafeInstance());
        }
        problem.setProperty("errors", List.of(ex.getMessage()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of(ex.getMessage()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "资源版本已变化，请刷新后重试");
        problem.setTitle("Conflict");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of("资源版本已变化，请刷新后重试"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail handlePessimisticLock(PessimisticLockingFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "资源版本已变化，请刷新后重试");
        problem.setTitle("Conflict");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of("资源版本已变化，请刷新后重试"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Rejected mutating request due to remaining references", ex);
        var message = "该资源仍被其他数据引用，无法删除";
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        problem.setTitle("Conflict");
        problem.setInstance(SAFE_ERROR_INSTANCE);
        problem.setProperty("errors", List.of(message));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in request", ex);
        var message = List.of(environment.getActiveProfiles()).contains("test")
                ? ex.getMessage()
                : "An unexpected error occurred";
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, message);
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
