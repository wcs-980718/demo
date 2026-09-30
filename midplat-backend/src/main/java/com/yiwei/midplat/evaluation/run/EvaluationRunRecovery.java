package com.yiwei.midplat.evaluation.run;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs only after the application is ready, so Flyway and JPA have finished before recovery scans persisted work. */
@Component
@EnableScheduling
public class EvaluationRunRecovery {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRunRecovery.class);
    private final EvaluationRunRepository runs;
    private final EvaluationRunStateService state;
    private final EvaluationRunDispatcher dispatcher;
    private final boolean enabled;
    private final java.time.Duration legacyStaleAfter;
    private final Clock clock;

    @Autowired
    EvaluationRunRecovery(EvaluationRunRepository runs, EvaluationRunStateService state, EvaluationRunDispatcher dispatcher,
            @Value("${midplat.evaluation.recovery.enabled:true}") boolean enabled,
            @Value("${midplat.evaluation.recovery.legacy-stale-seconds:600}") long legacyStaleSeconds) {
        this.runs = runs;
        this.state = state;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.legacyStaleAfter = java.time.Duration.ofSeconds(Math.max(600, legacyStaleSeconds));
        this.clock = Clock.systemUTC();
    }

    EvaluationRunRecovery(EvaluationRunRepository runs, EvaluationRunStateService state, EvaluationRunDispatcher dispatcher,
            boolean enabled) { this(runs, state, dispatcher, enabled, 600); }

    EvaluationRunRecovery(EvaluationRunRepository runs, EvaluationRunStateService state, EvaluationRunDispatcher dispatcher,
            boolean enabled, long legacyStaleSeconds, Clock clock) {
        this.runs = runs;
        this.state = state;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.legacyStaleAfter = java.time.Duration.ofSeconds(Math.max(600, legacyStaleSeconds));
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterApplicationReady() {
        if (enabled) recover();
    }

    @Scheduled(fixedDelayString = "${midplat.evaluation.recovery.scan-delay-ms:60000}")
    public void recoverPeriodically() { if (enabled) recover(); }

    /** Stable created-at ordering, with per-run isolation so one broken/audit-failed row cannot block later work. */
    public void recover() {
        Instant now = Instant.now(clock);
        List<EvaluationRun> candidates = runs.findAllByStatusInOrderByCreatedAtAsc(
                List.of(EvaluationRunStatus.QUEUED, EvaluationRunStatus.RUNNING));
        for (EvaluationRun candidate : candidates) {
            try {
                if (candidate.getStatus() == EvaluationRunStatus.QUEUED) {
                    dispatcher.dispatch(new EvaluationRunEvent(candidate.getId()));
                } else if (candidate.getStatus() == EvaluationRunStatus.RUNNING) {
                    state.recoverInterruptedRun(candidate.getId(), legacyStaleAfter, now);
                }
            } catch (RuntimeException exception) {
                log.warn("evaluation run recovery failed");
            }
        }
    }
}
