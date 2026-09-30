package com.yiwei.midplat.exceptionlog;

import com.yiwei.midplat.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "exception_report")
public class ExceptionReport extends BaseEntity {

    @Column(name = "platform_id", nullable = false, length = 64)
    private String platformId;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "query_params", columnDefinition = "text")
    private String queryParams;

    @Column(name = "request_body", columnDefinition = "text")
    private String requestBody;

    @Column(name = "status")
    private Integer status;

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ExceptionReport() {}

    public ExceptionReport(
            String id,
            String platformId,
            String url,
            String method,
            String queryParams,
            String requestBody,
            Integer status,
            String errorMessage,
            Instant occurredAt) {
        super(id);
        this.platformId = platformId;
        this.url = url;
        this.method = method;
        this.queryParams = queryParams;
        this.requestBody = requestBody;
        this.status = status;
        this.errorMessage = errorMessage;
        this.occurredAt = occurredAt;
    }

    public String getPlatformId() {
        return platformId;
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public String getQueryParams() {
        return queryParams;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public Integer getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
