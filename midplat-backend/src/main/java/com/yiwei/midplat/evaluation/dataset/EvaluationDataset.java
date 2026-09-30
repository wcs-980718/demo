package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.domain.BaseEntity;
import com.yiwei.midplat.common.domain.DomainAssertions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity(name = "EvaluationDataset")
@Table(name = "midplat_eval_dataset")
public class EvaluationDataset extends BaseEntity {

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "last_derived_at")
    private Instant lastDerivedAt;

    protected EvaluationDataset() {}

    public EvaluationDataset(String id, String name, String description) {
        super(id);
        this.name = DomainAssertions.requireText(name, "name 不能为空");
        this.description = blankToNull(description);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void recordVersionDerivation(long expectedVersion) {
        if (getVersion() != expectedVersion) {
            throw new com.yiwei.midplat.common.api.ConflictException("数据集版本已变化，请刷新后重试");
        }
        lastDerivedAt = Instant.now();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
