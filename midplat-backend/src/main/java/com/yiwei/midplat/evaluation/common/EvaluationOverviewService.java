package com.yiwei.midplat.evaluation.common;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationOverviewService {

    private final EvaluationOverviewReader reader;

    EvaluationOverviewService(EvaluationOverviewReader reader) {
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview() {
        EvaluationOverviewReader.Snapshot snapshot = reader.readSnapshot();
        return new Overview(snapshot.totalCases(), snapshot.reviewedCases(),
                snapshot.frozenVersions(), snapshot.runningRuns(),
                snapshot.latestTerminalPassRate(), snapshot.regressionCount());
    }

    public record Overview(long totalCases, long reviewedCases, long frozenVersions,
            long runningRuns, BigDecimal latestTerminalPassRate, long regressionCount) {}
}
