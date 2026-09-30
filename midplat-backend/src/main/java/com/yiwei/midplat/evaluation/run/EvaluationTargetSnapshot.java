package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.common.domain.DomainAssertions;
import java.math.BigDecimal;

/** Explicit allow-list for the immutable, credential-free evaluation target snapshot. */
public record EvaluationTargetSnapshot(
        String datasetVersionId,
        String datasetSnapshotHash,
        String platformId,
        String platformVersion,
        String modelId,
        String modelName,
        String baseUrl,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion,
        String promptId,
        String promptName,
        String promptVersion,
        String promptBody,
        String promptHash) {

    public EvaluationTargetSnapshot {
        datasetVersionId = DomainAssertions.requireText(datasetVersionId, "datasetVersionId 不能为空");
        datasetSnapshotHash = DomainAssertions.requireText(datasetSnapshotHash, "datasetSnapshotHash 不能为空");
        platformId = DomainAssertions.requireText(platformId, "platformId 不能为空");
        platformVersion = DomainAssertions.requireText(platformVersion, "platformVersion 不能为空");
        modelId = DomainAssertions.requireText(modelId, "modelId 不能为空");
        modelName = DomainAssertions.requireText(modelName, "modelName 不能为空");
        baseUrl = DomainAssertions.requireText(baseUrl, "baseUrl 不能为空");
        inputPricePerMillion = requirePrice(inputPricePerMillion, "inputPricePerMillion");
        outputPricePerMillion = requirePrice(outputPricePerMillion, "outputPricePerMillion");
        promptId = DomainAssertions.requireText(promptId, "promptId 不能为空");
        promptName = DomainAssertions.requireText(promptName, "promptName 不能为空");
        promptVersion = DomainAssertions.requireText(promptVersion, "promptVersion 不能为空");
        promptBody = DomainAssertions.requireText(promptBody, "promptBody 不能为空");
        promptHash = DomainAssertions.requireText(promptHash, "promptHash 不能为空");
    }
    @Override public String toString() { return "EvaluationTargetSnapshot[datasetVersionId="+datasetVersionId+", platformId="+platformId+", modelId="+modelId+", promptBody=[redacted]]"; }

    private static BigDecimal requirePrice(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(field + " 必须非负");
        return value;
    }
}
