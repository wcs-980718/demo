package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.evaluation.casecenter.CaseSeverity;
import com.yiwei.midplat.evaluation.casecenter.EvaluatorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity(name = "EvaluationDatasetItem")
@Table(name = "midplat_eval_dataset_item")
public class EvaluationDatasetItem extends BaseEntity {

    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "case_id", nullable = false, length = 64)
    private String caseId;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(name = "snapshot_name", length = 128)
    private String snapshotName;

    @Column(name = "snapshot_category", length = 64)
    private String snapshotCategory;

    @Column(name = "snapshot_severity", length = 16)
    private String snapshotSeverity;

    @Column(name = "snapshot_input_text", columnDefinition = "text")
    private String snapshotInputText;

    @Column(name = "snapshot_expected_json", columnDefinition = "text")
    private String snapshotExpectedJson;

    @Column(name = "snapshot_evaluator_type", length = 32)
    private String snapshotEvaluatorType;

    @Column(name = "snapshot_content_hash", length = 64)
    private String snapshotContentHash;

    protected EvaluationDatasetItem() {}

    private EvaluationDatasetItem(String id, String versionId, String caseId, int orderNo) {
        super(id);
        this.versionId = DomainAssertions.requireText(versionId, "versionId 不能为空");
        this.caseId = DomainAssertions.requireText(caseId, "caseId 不能为空");
        if (orderNo < 1) {
            throw new IllegalArgumentException("orderNo 必须大于 0");
        }
        this.orderNo = orderNo;
    }

    public static EvaluationDatasetItem forDraft(String id, String versionId, String caseId, int orderNo) {
        return new EvaluationDatasetItem(id, versionId, caseId, orderNo);
    }

    public String getVersionId() {
        return versionId;
    }

    public String getCaseId() {
        return caseId;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getSnapshotCategory() {
        return snapshotCategory;
    }

    public String getSnapshotSeverity() {
        return snapshotSeverity;
    }

    public String getSnapshotInputText() {
        return snapshotInputText;
    }

    public String getSnapshotExpectedJson() {
        return snapshotExpectedJson;
    }

    public String getSnapshotEvaluatorType() {
        return snapshotEvaluatorType;
    }

    public String getSnapshotContentHash() {
        return snapshotContentHash;
    }

    public void freezeSnapshot(
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            String contentHash) {
        if (snapshotContentHash != null) {
            throw new ConflictException("冻结快照不可替换");
        }
        this.snapshotName = DomainAssertions.requireText(name, "snapshotName 不能为空");
        this.snapshotCategory = DomainAssertions.requireText(category, "snapshotCategory 不能为空");
        this.snapshotSeverity = DomainAssertions.requireText(severity.name(), "snapshotSeverity 不能为空");
        this.snapshotInputText = DomainAssertions.requireText(inputText, "snapshotInputText 不能为空");
        this.snapshotExpectedJson = DomainAssertions.requireText(expectedJson, "snapshotExpectedJson 不能为空");
        this.snapshotEvaluatorType = DomainAssertions.requireText(evaluatorType.name(), "snapshotEvaluatorType 不能为空");
        this.snapshotContentHash = DomainAssertions.requireText(contentHash, "snapshotContentHash 不能为空");
    }
}
