package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import com.yiwei.midplat.common.api.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity(name = "EvaluationDatasetVersion")
@Table(name = "midplat_eval_dataset_version")
public class EvaluationDatasetVersion extends BaseEntity {

    @Column(name = "dataset_id", nullable = false, length = 64)
    private String datasetId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DatasetVersionStatus status;

    @Column(name = "snapshot_hash", length = 64)
    private String snapshotHash;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "draft_slot")
    private Integer draftSlot;

    @Column(name = "item_revision", nullable = false)
    private int itemRevision;

    protected EvaluationDatasetVersion() {}

    private EvaluationDatasetVersion(String id, String datasetId, int versionNo, DatasetVersionStatus status) {
        super(id);
        this.datasetId = DomainAssertions.requireText(datasetId, "datasetId 不能为空");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo 必须大于 0");
        }
        this.versionNo = versionNo;
        this.status = status;
        this.draftSlot = status == DatasetVersionStatus.DRAFT ? 1 : null;
    }

    public static EvaluationDatasetVersion createDraft(String id, String datasetId, int versionNo) {
        return new EvaluationDatasetVersion(id, datasetId, versionNo, DatasetVersionStatus.DRAFT);
    }

    public void freeze(String snapshotHash, Instant frozenAt, long expectedVersion) {
        requireVersion(expectedVersion);
        if (status != DatasetVersionStatus.DRAFT) {
            throw new ConflictException("只有草稿版本可以冻结");
        }
        this.snapshotHash = DomainAssertions.requireText(snapshotHash, "snapshotHash 不能为空");
        this.frozenAt = Objects.requireNonNull(frozenAt, "frozenAt 不能为空");
        this.status = DatasetVersionStatus.FROZEN;
        this.draftSlot = null;
    }

    public void replaceItems(long expectedVersion) {
        requireVersion(expectedVersion);
        if (status != DatasetVersionStatus.DRAFT) {
            throw new ConflictException("只有草稿版本可以编排案例");
        }
        itemRevision++;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public DatasetVersionStatus getStatus() {
        return status;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    private void requireVersion(long expectedVersion) {
        if (getVersion() != expectedVersion) {
            throw new ConflictException("数据集版本已变化，请刷新后重试");
        }
    }
}
