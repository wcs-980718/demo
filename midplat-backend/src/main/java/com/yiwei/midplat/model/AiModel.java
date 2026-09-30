package com.yiwei.midplat.model;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "midplat_model")
public class AiModel extends BaseEntity {

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private ModelKind kind;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "api_key", length = 256)
    private String apiKey;

    @Column(name = "ping_status", nullable = false, length = 32)
    private String pingStatus;

    @Column(name = "input_price_per_million", nullable = false, precision = 20, scale = 8)
    private BigDecimal inputPricePerMillion;

    @Column(name = "output_price_per_million", nullable = false, precision = 20, scale = 8)
    private BigDecimal outputPricePerMillion;

    @Column(name = "supports_tool_calls") private Boolean supportsToolCalls;
    @Column(name = "supports_json_object") private Boolean supportsJsonObject;

    public Boolean getSupportsToolCalls() { return supportsToolCalls; }
    public Boolean getSupportsJsonObject() { return supportsJsonObject; }
    public void updateCapabilities(Boolean toolCalls, Boolean jsonObject) {
        supportsToolCalls = toolCalls; supportsJsonObject = jsonObject;
    }
    protected AiModel() {}

    public AiModel(String id, String name, ModelKind kind, String modelName, String baseUrl, String apiKey,
            BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        super(id);
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.kind = kind;
        this.modelName = DomainAssertions.requireText(modelName, "model cannot be blank");
        this.baseUrl = DomainAssertions.requireText(baseUrl, "base_url cannot be blank");
        this.apiKey = apiKey;
        this.pingStatus = "untested";
        this.inputPricePerMillion = requirePriceOrZero(inputPricePerMillion, "inputPricePerMillion");
        this.outputPricePerMillion = requirePriceOrZero(outputPricePerMillion, "outputPricePerMillion");
    }

    public void update(String name, ModelKind kind, String modelName, String baseUrl, String apiKey,
            BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this.name = DomainAssertions.requireText(name, "name cannot be blank");
        this.kind = kind;
        this.modelName = DomainAssertions.requireText(modelName, "model cannot be blank");
        this.baseUrl = DomainAssertions.requireText(baseUrl, "base_url cannot be blank");
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKey = apiKey;
        }
        if (inputPricePerMillion != null) {
            this.inputPricePerMillion = requirePrice(inputPricePerMillion, "inputPricePerMillion");
        }
        if (outputPricePerMillion != null) {
            this.outputPricePerMillion = requirePrice(outputPricePerMillion, "outputPricePerMillion");
        }
        this.pingStatus = "untested";
    }

    private static BigDecimal requirePriceOrZero(BigDecimal price, String field) {
        return price == null ? BigDecimal.ZERO : requirePrice(price, field);
    }

    private static BigDecimal requirePrice(BigDecimal value, String field) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
        if (value.scale() > 8 || value.precision() - value.scale() > 12) {
            throw new IllegalArgumentException(field + " 整数最多 12 位且小数最多 8 位");
        }
        return value;
    }

    public void markPing(String status) {
        this.pingStatus = status;
    }

    public String getName() { return name; }
    public ModelKind getKind() { return kind; }
    public String getModelName() { return modelName; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getPingStatus() { return pingStatus; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
}
