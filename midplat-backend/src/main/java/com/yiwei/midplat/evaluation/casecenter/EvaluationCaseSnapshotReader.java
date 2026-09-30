package com.yiwei.midplat.evaluation.casecenter;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationCaseSnapshotReader {

    private final EvaluationCaseRepository repository;

    EvaluationCaseSnapshotReader(EvaluationCaseRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Set<String> findExistingIds(Collection<String> caseIds) {
        return repository.findIdsByIdIn(caseIds);
    }

    @Transactional
    public List<CaseSnapshot> lockSnapshotsByIds(Collection<String> caseIds) {
        return repository.findAllByIdInForUpdate(sortCaseIds(caseIds)).stream()
                .map(CaseSnapshot::from)
                .toList();
    }

    static List<String> sortCaseIds(Collection<String> caseIds) {
        return caseIds.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public record CaseSnapshot(
            String id,
            String name,
            String category,
            CaseSeverity severity,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType,
            CaseReviewStatus reviewStatus,
            String contentHash,
            CaseLifecycleStatus lifecycleStatus) {

        @Override
        public String toString() {
            return "CaseSnapshot[id=" + id + ", name=" + name + ", inputText=[redacted], expectedJson=[redacted]]";
        }

        static CaseSnapshot from(EvaluationCase source) {
            return new CaseSnapshot(
                    source.getId(),
                    source.getName(),
                    source.getCategory(),
                    source.getSeverity(),
                    source.getInputText(),
                    source.getExpectedJson(),
                    source.getEvaluatorType(),
                    source.getReviewStatus(),
                    source.getContentHash(),
                    source.getLifecycleStatus());
        }
    }
}
