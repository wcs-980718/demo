package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.casecenter.CaseLifecycleStatus;
import com.yiwei.midplat.evaluation.casecenter.CaseReviewStatus;
import com.yiwei.midplat.evaluation.casecenter.EvaluationAuditService;
import com.yiwei.midplat.evaluation.casecenter.EvaluationCaseSnapshotReader;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationDatasetService {

    private static final URI SAFE_DATASET_INSTANCE = URI.create("/api/evaluation/datasets");

    private final EvaluationDatasetRepository datasets;
    private final EvaluationDatasetVersionRepository versions;
    private final EvaluationDatasetItemRepository items;
    private final EvaluationCaseSnapshotReader caseReader;
    private final EvaluationAuditService auditService;
    private final EntityManager entityManager;

    EvaluationDatasetService(
            EvaluationDatasetRepository datasets,
            EvaluationDatasetVersionRepository versions,
            EvaluationDatasetItemRepository items,
            EvaluationCaseSnapshotReader caseReader,
            EvaluationAuditService auditService,
            EntityManager entityManager) {
        this.datasets = datasets;
        this.versions = versions;
        this.items = items;
        this.caseReader = caseReader;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    @Transactional
    public DatasetView create(CreateDatasetCmd cmd) {
        EvaluationDataset dataset = datasets.save(new EvaluationDataset(
                Identities.newId(), cmd.name(), cmd.description()));
        EvaluationDatasetVersion version = versions.save(EvaluationDatasetVersion.createDraft(
                Identities.newId(), dataset.getId(), 1));
        auditService.recordResource("DATASET_CREATED", "EVALUATION_DATASET", dataset.getId(), "MANUAL", Map.of(
                "name", dataset.getName(),
                "versionNo", version.getVersionNo(),
                "status", version.getStatus().name()));
        entityManager.flush();
        return toDatasetView(dataset, List.of(version));
    }

    @Transactional(readOnly = true)
    public DatasetView get(String datasetId) {
        EvaluationDataset dataset = requireDataset(datasetId);
        return toDatasetView(dataset, versions.findAllByDatasetIdOrderByVersionNoDesc(datasetId));
    }

    @Transactional(readOnly = true)
    public List<DatasetSummaryView> list() {
        return datasets.findAllByOrderByUpdatedAtDesc().stream()
                .map(dataset -> new DatasetSummaryView(
                        dataset.getId(), dataset.getName(), dataset.getDescription(), dataset.getVersion()))
                .toList();
    }

    @Transactional
    public DatasetVersionView replaceItems(String versionId, List<String> caseIds, long expectedVersion) {
        EvaluationDatasetVersion version = requireVersionForUpdate(versionId);
        List<String> normalizedCaseIds = validateCaseIds(caseIds);
        requireAllCasesExist(normalizedCaseIds);
        version.replaceItems(expectedVersion);
        items.removeAllForVersion(versionId);
        for (int index = 0; index < normalizedCaseIds.size(); index++) {
            items.save(EvaluationDatasetItem.forDraft(
                    Identities.newId(), versionId, normalizedCaseIds.get(index), index + 1));
        }
        auditService.recordResource("DATASET_ITEMS_REPLACED", "EVALUATION_DATASET_VERSION", versionId, "MANUAL", Map.of(
                "versionNo", version.getVersionNo(),
                "status", version.getStatus().name(),
                "itemCount", normalizedCaseIds.size()));
        versions.save(version);
        entityManager.flush();
        return toVersionView(version);
    }

    @Transactional
    public DatasetVersionView freeze(String versionId, long expectedVersion) {
        EvaluationDatasetVersion initial = requireVersion(versionId);
        requireDatasetForUpdate(initial.getDatasetId());
        EvaluationDatasetVersion version = requireVersionForUpdate(versionId);
        List<EvaluationDatasetItem> versionItems = items.findAllByVersionIdOrderByOrderNoAsc(versionId);
        if (versionItems.isEmpty()) {
            throw new ConflictException("空数据集版本不能冻结");
        }
        Map<String, EvaluationCaseSnapshotReader.CaseSnapshot> sourceById = lockAndIndexCases(versionItems);
        for (EvaluationDatasetItem item : versionItems) {
            EvaluationCaseSnapshotReader.CaseSnapshot source = sourceById.get(item.getCaseId());
            requireFreezeEligible(source);
            item.freezeSnapshot(
                    source.name(),
                    source.category(),
                    source.severity(),
                    source.inputText(),
                    source.expectedJson(),
                    source.evaluatorType(),
                    source.contentHash());
        }
        String snapshotHash = DatasetSnapshotHasher.hash(versionItems.stream()
                .map(item -> new DatasetSnapshotHasher.Entry(item.getCaseId(), item.getSnapshotContentHash()))
                .toList());
        version.freeze(snapshotHash, Instant.now(), expectedVersion);
        auditService.recordResource("DATASET_VERSION_FROZEN", "EVALUATION_DATASET_VERSION", versionId, "MANUAL", Map.of(
                "versionNo", version.getVersionNo(),
                "status", version.getStatus().name(),
                "itemCount", versionItems.size(),
                "snapshotHash", snapshotHash));
        entityManager.flush();
        return toVersionView(version);
    }

    @Transactional
    public DatasetVersionView deriveDraft(String datasetId, long expectedDatasetVersion) {
        EvaluationDataset dataset = requireDatasetForUpdate(datasetId);
        dataset.recordVersionDerivation(expectedDatasetVersion);
        if (versions.existsByDatasetIdAndStatus(datasetId, DatasetVersionStatus.DRAFT)) {
            throw new ConflictException("数据集已有草稿版本");
        }
        List<EvaluationDatasetVersion> existing = versions.findAllByDatasetIdOrderByVersionNoDesc(datasetId);
        EvaluationDatasetVersion source = existing.stream()
                .filter(version -> version.getStatus() == DatasetVersionStatus.FROZEN)
                .findFirst()
                .orElseThrow(() -> new ConflictException("数据集没有可派生的冻结版本"));
        int nextVersionNo = existing.stream().mapToInt(EvaluationDatasetVersion::getVersionNo).max().orElse(0) + 1;
        EvaluationDatasetVersion derived = versions.save(EvaluationDatasetVersion.createDraft(
                Identities.newId(), datasetId, nextVersionNo));
        List<EvaluationDatasetItem> sourceItems = items.findAllByVersionIdOrderByOrderNoAsc(source.getId());
        for (EvaluationDatasetItem sourceItem : sourceItems) {
            items.save(EvaluationDatasetItem.forDraft(
                    Identities.newId(), derived.getId(), sourceItem.getCaseId(), sourceItem.getOrderNo()));
        }
        auditService.recordResource("DATASET_VERSION_DERIVED", "EVALUATION_DATASET_VERSION", derived.getId(), "MANUAL", Map.of(
                "versionNo", derived.getVersionNo(),
                "sourceVersionNo", source.getVersionNo(),
                "status", derived.getStatus().name(),
                "itemCount", sourceItems.size()));
        entityManager.flush();
        return toVersionView(derived);
    }

    private EvaluationDataset requireDataset(String datasetId) {
        return datasets.findById(datasetId).orElseThrow(
                () -> new ResourceNotFoundException("评测数据集不存在", SAFE_DATASET_INSTANCE));
    }

    private EvaluationDataset requireDatasetForUpdate(String datasetId) {
        return datasets.findByIdForUpdate(datasetId).orElseThrow(
                () -> new ResourceNotFoundException("评测数据集不存在", SAFE_DATASET_INSTANCE));
    }

    private EvaluationDatasetVersion requireVersion(String versionId) {
        return versions.findById(versionId).orElseThrow(
                () -> new ResourceNotFoundException("评测数据集版本不存在", SAFE_DATASET_INSTANCE));
    }

    private EvaluationDatasetVersion requireVersionForUpdate(String versionId) {
        return versions.findByIdForUpdate(versionId).orElseThrow(
                () -> new ResourceNotFoundException("评测数据集版本不存在", SAFE_DATASET_INSTANCE));
    }

    private List<String> validateCaseIds(List<String> caseIds) {
        if (caseIds == null) {
            throw new IllegalArgumentException("caseIds 不能为空");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String caseId : caseIds) {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId 不能为空");
            }
            String value = caseId.trim();
            if (!seen.add(value)) {
                throw new IllegalArgumentException("案例不能重复编入同一版本");
            }
            normalized.add(value);
        }
        return normalized;
    }

    private void requireAllCasesExist(List<String> caseIds) {
        if (caseIds.isEmpty()) {
            return;
        }
        if (caseReader.findExistingIds(caseIds).size() != caseIds.size()) {
            throw new IllegalArgumentException("存在不存在的评测案例");
        }
    }

    private Map<String, EvaluationCaseSnapshotReader.CaseSnapshot> lockAndIndexCases(
            List<EvaluationDatasetItem> versionItems) {
        List<String> caseIds = versionItems.stream().map(EvaluationDatasetItem::getCaseId).toList();
        List<EvaluationCaseSnapshotReader.CaseSnapshot> lockedCases = caseReader.lockSnapshotsByIds(caseIds);
        if (lockedCases.size() != caseIds.size()) {
            throw new ConflictException("数据集包含不存在的评测案例");
        }
        Map<String, EvaluationCaseSnapshotReader.CaseSnapshot> sourceById = new java.util.HashMap<>();
        lockedCases.forEach(source -> sourceById.put(source.id(), source));
        return sourceById;
    }

    private void requireFreezeEligible(EvaluationCaseSnapshotReader.CaseSnapshot source) {
        if (source.reviewStatus() != CaseReviewStatus.REVIEWED
                || source.lifecycleStatus() != CaseLifecycleStatus.ACTIVE) {
            throw new ConflictException("冻结仅允许 ACTIVE 且 REVIEWED 的评测案例");
        }
    }

    private DatasetView toDatasetView(EvaluationDataset dataset, List<EvaluationDatasetVersion> datasetVersions) {
        return new DatasetView(
                dataset.getId(),
                dataset.getName(),
                dataset.getDescription(),
                dataset.getVersion(),
                datasetVersions.stream().map(this::toVersionView).toList());
    }

    private DatasetVersionView toVersionView(EvaluationDatasetVersion version) {
        return new DatasetVersionView(
                version.getId(),
                version.getDatasetId(),
                version.getVersionNo(),
                version.getStatus(),
                version.getSnapshotHash(),
                version.getFrozenAt(),
                version.getVersion(),
                items.findAllByVersionIdOrderByOrderNoAsc(version.getId()).stream()
                        .map(item -> new DatasetItemView(
                                item.getCaseId(),
                                item.getOrderNo(),
                                item.getSnapshotName(),
                                item.getSnapshotCategory(),
                                item.getSnapshotSeverity(),
                                item.getSnapshotInputText(),
                                item.getSnapshotExpectedJson(),
                                item.getSnapshotEvaluatorType(),
                                item.getSnapshotContentHash()))
                        .toList());
    }

    public record CreateDatasetCmd(String name, String description) {}

    public record DatasetView(
            String id,
            String name,
            String description,
            long version,
            List<DatasetVersionView> versions) {}

    public record DatasetSummaryView(String id, String name, String description, long version) {}

    public record DatasetVersionView(
            String id,
            String datasetId,
            int versionNo,
            DatasetVersionStatus status,
            String snapshotHash,
            Instant frozenAt,
            long version,
            List<DatasetItemView> items) {}

    public record DatasetItemView(
            String caseId,
            int orderNo,
            String snapshotName,
            String snapshotCategory,
            String snapshotSeverity,
            String snapshotInputText,
            String snapshotExpectedJson,
            String snapshotEvaluatorType,
            String snapshotContentHash) {
        @Override public String toString() { return "DatasetItemView[caseId=" + caseId + ", snapshotInputText=[redacted], snapshotExpectedJson=[redacted]]"; }
    }
}
