package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only boundary exposing only already frozen, ordered evaluation content. */
@Service
public class EvaluationDatasetRunSnapshotReader {
    private final EvaluationDatasetVersionRepository versions;
    private final EvaluationDatasetItemRepository items;

    EvaluationDatasetRunSnapshotReader(EvaluationDatasetVersionRepository versions, EvaluationDatasetItemRepository items) {
        this.versions = versions;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public RunDatasetSnapshot requireFrozen(String versionId) {
        EvaluationDatasetVersion version = versions.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("evaluation dataset version not found"));
        if (version.getStatus() != DatasetVersionStatus.FROZEN) throw new ConflictException("数据集版本必须已冻结");
        return new RunDatasetSnapshot(version.getId(), version.getSnapshotHash(), items.findAllByVersionIdOrderByOrderNoAsc(versionId).stream()
                .map(item -> new RunDatasetItem(item.getCaseId(), item.getOrderNo(), item.getSnapshotName(),
                        item.getSnapshotCategory(), item.getSnapshotSeverity(), item.getSnapshotInputText(),
                        item.getSnapshotExpectedJson(), item.getSnapshotEvaluatorType(), item.getSnapshotContentHash()))
                .toList());
    }

    public record RunDatasetSnapshot(String versionId, String snapshotHash, List<RunDatasetItem> items) { @Override public String toString() { return "RunDatasetSnapshot[versionId="+versionId+", snapshotHash="+snapshotHash+", items=[redacted]]"; } }
    public record RunDatasetItem(String caseId, int orderNo, String name, String category, String severity,
            String inputText, String expectedJson, String evaluatorType, String contentHash) { @Override public String toString() { return "RunDatasetItem[caseId="+caseId+", inputText=[redacted], expectedJson=[redacted]]"; } }
}
