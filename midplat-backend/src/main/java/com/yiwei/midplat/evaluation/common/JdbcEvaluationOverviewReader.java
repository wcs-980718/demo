package com.yiwei.midplat.evaluation.common;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class JdbcEvaluationOverviewReader implements EvaluationOverviewReader {

    private static final String OVERVIEW_SQL = """
            with latest_terminal as (
                select id, dataset_version_id, platform_id, passed_count, failed_count, completed_at
                  from midplat_eval_run
                 where status in ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
                 order by completed_at desc, id desc
                 limit 1
            ),
            comparable_baseline as (
                select r.id, r.completed_at
                  from midplat_eval_run r
                  join latest_terminal candidate
                    on r.platform_id = candidate.platform_id
                   and r.dataset_version_id = candidate.dataset_version_id
                   and r.id <> candidate.id
                 where r.status in ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
                   and not exists (
                       select 1
                         from midplat_eval_result candidate_result
                        where candidate_result.run_id = candidate.id
                          and not exists (
                              select 1
                                from midplat_eval_result baseline_result
                               where baseline_result.run_id = r.id
                                 and baseline_result.case_id = candidate_result.case_id
                                 and baseline_result.snapshot_content_hash = candidate_result.snapshot_content_hash
                          )
                   )
                   and not exists (
                       select 1
                         from midplat_eval_result baseline_result
                        where baseline_result.run_id = r.id
                          and not exists (
                              select 1
                                from midplat_eval_result candidate_result
                               where candidate_result.run_id = candidate.id
                                 and candidate_result.case_id = baseline_result.case_id
                                 and candidate_result.snapshot_content_hash = baseline_result.snapshot_content_hash
                          )
                   )
                 order by r.completed_at desc, r.id desc
                 limit 1
            ),
            alignment as (
                select
                    (select count(*)
                       from midplat_eval_result candidate_result
                       join latest_terminal candidate on candidate_result.run_id = candidate.id) as candidate_count,
                    (select count(*)
                       from midplat_eval_result baseline_result
                       join comparable_baseline baseline on baseline_result.run_id = baseline.id) as baseline_count,
                    (select count(*)
                       from midplat_eval_result candidate_result
                       join latest_terminal candidate on candidate_result.run_id = candidate.id
                       join comparable_baseline baseline on 1 = 1
                       join midplat_eval_result baseline_result
                         on baseline_result.run_id = baseline.id
                        and baseline_result.case_id = candidate_result.case_id
                        and baseline_result.snapshot_content_hash = candidate_result.snapshot_content_hash) as matched_count
            ),
            regressed_cases as (
                select count(*) as regression_count
                  from midplat_eval_result candidate_result
                  join latest_terminal candidate on candidate_result.run_id = candidate.id
                  join comparable_baseline baseline on 1 = 1
                  join midplat_eval_result baseline_result
                    on baseline_result.run_id = baseline.id
                   and baseline_result.case_id = candidate_result.case_id
                   and baseline_result.snapshot_content_hash = candidate_result.snapshot_content_hash
                 where baseline_result.status not in ('ERROR', 'CANCELLED')
                   and candidate_result.status not in ('ERROR', 'CANCELLED')
                   and not (baseline_result.status = 'PENDING' and baseline_result.review_required)
                   and not (candidate_result.status = 'PENDING' and candidate_result.review_required)
                   and (
                        (baseline_result.status = 'PASSED' and candidate_result.status <> 'PASSED')
                        or (
                            (
                                (baseline_result.status = 'PASSED' and candidate_result.status = 'PASSED')
                                or (baseline_result.status <> 'PASSED' and candidate_result.status <> 'PASSED')
                            )
                            and baseline_result.score is not null
                            and candidate_result.score is not null
                            and candidate_result.score < baseline_result.score
                        )
                   )
            )
            select
                (select count(*) from midplat_eval_case) as total_case_count,
                (select count(*) from midplat_eval_case where review_status = 'REVIEWED') as reviewed_case_count,
                (select count(*) from midplat_eval_dataset_version where status = 'FROZEN') as frozen_version_count,
                (select count(*) from midplat_eval_run where status in ('QUEUED', 'RUNNING')) as running_run_count,
                coalesce((
                    select cast(passed_count as numeric(19, 4)) * 100
                           / nullif(passed_count + failed_count, 0)
                      from latest_terminal
                ), 0) as latest_terminal_pass_rate,
                case
                    when (select candidate_count from alignment) = (select baseline_count from alignment)
                     and (select candidate_count from alignment) = (select matched_count from alignment)
                    then (select regression_count from regressed_cases)
                    else 0
                end as regression_count
            """;

    private final JdbcTemplate jdbc;

    JdbcEvaluationOverviewReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot readSnapshot() {
        return jdbc.queryForObject(OVERVIEW_SQL, (result, rowNumber) -> new Snapshot(
                result.getLong("total_case_count"),
                result.getLong("reviewed_case_count"),
                result.getLong("frozen_version_count"),
                result.getLong("running_run_count"),
                zeroIfNull(result.getBigDecimal("latest_terminal_pass_rate")),
                result.getLong("regression_count")));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
