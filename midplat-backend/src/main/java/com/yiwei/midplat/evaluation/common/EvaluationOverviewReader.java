package com.yiwei.midplat.evaluation.common;

import java.math.BigDecimal;

/** Narrow read port for the cross-context evaluation overview projection. */
public interface EvaluationOverviewReader {

    Snapshot readSnapshot();

    record Snapshot(long totalCases, long reviewedCases, long frozenVersions,
            long runningRuns, BigDecimal latestTerminalPassRate, long regressionCount) {}
}
