package com.yiwei.midplat.exceptionlog;

import com.yiwei.midplat.common.api.ApiResponse;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.fusion.FusionAccess;
import com.yiwei.midplat.platform.PlatformCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异常历史：各项目调用接口报错时主动 POST 上报（Bearer 凭证标识来源项目），
 * 中台脱敏落库；管理端分页查询、按 id 看详情。W1 起 GET 列表/详情要求融合管理身份。
 */
@RestController
@RequestMapping("/api/exception-reports")
public class ExceptionReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformCredentialService credentials;
    private final ExceptionReportRepository repository;

    ExceptionReportController(PlatformCredentialService credentials, ExceptionReportRepository repository) {
        this.credentials = credentials;
        this.repository = repository;
    }

    public record ReportRequest(
            @NotBlank @Size(max = 2048) String url,
            @NotBlank @Size(max = 10) String method,
            @Size(max = 4000) String queryParams,
            String requestBody,
            Integer status,
            @NotBlank String errorMessage,
            Instant occurredAt) {}

    public record ReportSummary(
            String id,
            String platformId,
            String url,
            String method,
            Integer status,
            String errorMessage,
            String category,
            String categoryLabel,
            String categoryDetail,
            boolean categoryFault,
            Instant occurredAt,
            Instant createdAt) {}

    public record ReportDetail(
            String id,
            String platformId,
            String url,
            String method,
            String queryParams,
            String requestBody,
            Integer status,
            String errorMessage,
            String category,
            String categoryLabel,
            String categoryDetail,
            Instant occurredAt,
            Instant createdAt) {}

    public record ReportPage(List<ReportSummary> content, long totalElements, int totalPages, int page, int size) {}

    @PostMapping
    ApiResponse<ReportDetail> report(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ReportRequest body) {
        // W1: the reporting project comes from the server-resolved credential, never from the body.
        String platformId = credentials.requireCallerProjectId(authorization);
        var report = new ExceptionReport(
                Identities.newId(),
                platformId,
                body.url(),
                body.method().toUpperCase(),
                PayloadSanitizer.truncate(body.queryParams(), PayloadSanitizer.QUERY_PARAMS_LIMIT),
                PayloadSanitizer.sanitizeBody(body.requestBody()),
                body.status(),
                PayloadSanitizer.truncate(body.errorMessage(), PayloadSanitizer.ERROR_MESSAGE_LIMIT),
                body.occurredAt() == null ? Instant.now() : body.occurredAt());
        return ApiResponse.ok(toDetail(repository.save(report)));
    }

    @GetMapping
    ApiResponse<ReportPage> list(
            HttpServletRequest managementRequest,
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireManagement(managementRequest, platformId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var result = repository.findAll(
                ExceptionReportSpecifications.matching(
                        platformId,
                        method,
                        status,
                        keyword,
                        occurredFrom,
                        occurredTo),
                pageable.withSort(org.springframework.data.domain.Sort.by("createdAt").descending()));
        return ApiResponse.ok(new ReportPage(
                result.getContent().stream().map(ExceptionReportController::toSummary).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()));
    }

    @GetMapping("/{id}")
    ApiResponse<ReportDetail> detail(@PathVariable String id, HttpServletRequest managementRequest) {
        requireManagement(managementRequest, null);
        var report = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("异常记录不存在"));
        return ApiResponse.ok(toDetail(report));
    }

    /** Management identity + project scope; project admins see their project, fusion admins see all. */
    private static void requireManagement(HttpServletRequest request, String requestedPlatformId) {
        FusionAccess.Identity identity = FusionAccess.identity(request);
        if (identity == null) {
            throw new com.yiwei.midplat.common.api.UnauthorizedException("需要融合管理会话");
        }
        if (identity.admin()) {
            return;
        }
        if (requestedPlatformId == null || !identity.projects().contains(requestedPlatformId)) {
            throw new com.yiwei.midplat.common.api.ForbiddenException("没有该项目的异常记录访问权限");
        }
    }

    private static ReportSummary toSummary(ExceptionReport report) {
        var view = ErrorCategory.classify(report.getStatus(), report.getErrorMessage(), report.getUrl());
        return new ReportSummary(
                report.getId(),
                report.getPlatformId(),
                report.getUrl(),
                report.getMethod(),
                report.getStatus(),
                PayloadSanitizer.truncate(report.getErrorMessage(), 120),
                view.category(),
                view.label(),
                view.detail(),
                view.fault(),
                report.getOccurredAt(),
                report.getCreatedAt());
    }

    private static ReportDetail toDetail(ExceptionReport report) {
        var view = ErrorCategory.classify(report.getStatus(), report.getErrorMessage(), report.getUrl());
        return new ReportDetail(
                report.getId(),
                report.getPlatformId(),
                report.getUrl(),
                report.getMethod(),
                report.getQueryParams(),
                report.getRequestBody(),
                report.getStatus(),
                report.getErrorMessage(),
                view.category(),
                view.label(),
                view.detail(),
                report.getOccurredAt(),
                report.getCreatedAt());
    }
}
