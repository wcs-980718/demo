package com.yiwei.midplat.evaluation.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.casecenter.EvaluationAuditService;
import com.yiwei.midplat.evaluation.dataset.EvaluationDatasetRunSnapshotReader;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.platform.PlatformService;
import com.yiwei.midplat.prompt.PromptService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationRunService {
    private final EvaluationRunRepository runs; private final EvaluationResultRepository results;
    private final EvaluationDatasetRunSnapshotReader datasets; private final PlatformService platforms;
    private final ModelService models; private final PromptService prompts; private final EvaluationAuditService audit;
    private final ApplicationEventPublisher events; private final ObjectMapper objectMapper; private final EvaluationRunStateService state;
    private final EntityManager entityManager;

    EvaluationRunService(EvaluationRunRepository runs, EvaluationResultRepository results, EvaluationDatasetRunSnapshotReader datasets,
            PlatformService platforms, ModelService models, PromptService prompts, EvaluationAuditService audit,
            ApplicationEventPublisher events, ObjectMapper objectMapper, EvaluationRunStateService state, EntityManager entityManager) {
        this.runs=runs;this.results=results;this.datasets=datasets;this.platforms=platforms;this.models=models;this.prompts=prompts;
        this.audit=audit;this.events=events;this.objectMapper=objectMapper;this.state=state;this.entityManager=entityManager;
    }

    @Transactional
    public RunSummary create(CreateRunCommand command) {
        EvaluationDatasetRunSnapshotReader.RunDatasetSnapshot dataset = datasets.requireFrozen(command.datasetVersionId());
        PlatformService.EvaluationPlatformSnapshot platform = platforms.requireEvaluationSnapshot(command.platformId());
        ModelService.EvaluationModelSnapshot model = models.requireEvaluationSnapshot(command.modelId());
        if (model.kind() != com.yiwei.midplat.model.ModelKind.llm) throw new IllegalArgumentException("模型必须为 LLM");
        PromptService.EvaluationPromptSnapshot prompt = prompts.requireEvaluationSnapshot(command.promptId());
        EvaluationTargetSnapshot target = new EvaluationTargetSnapshot(dataset.versionId(), dataset.snapshotHash(), platform.id(), platform.version(),
                model.id(), model.modelName(), model.baseUrl(), model.inputPricePerMillion(), model.outputPricePerMillion(), prompt.id(),
                prompt.name(), prompt.version(), prompt.body(), prompt.bodySha256());
        EvaluationRun run = runs.save(EvaluationRun.create(Identities.newId(), command.datasetVersionId(), command.platformId(), command.modelId(),
                command.promptId(), target, new EvaluationRunParameters(command.temperature(), command.maxTokens(), command.timeoutMs()), dataset.items().size()));
        for (EvaluationDatasetRunSnapshotReader.RunDatasetItem item : dataset.items()) results.save(EvaluationResult.pending(Identities.newId(), run.getId(),
                item.caseId(), item.orderNo(), item.name(), item.category(), item.severity(), item.inputText(), item.expectedJson(), item.evaluatorType(), item.contentHash()));
        audit.recordResource("RUN_CREATED", "EVALUATION_RUN", run.getId(), "MANUAL", Map.of("status", run.getStatus().name(), "itemCount", run.getTotalCount(), "snapshotHash", dataset.snapshotHash()));
        events.publishEvent(new EvaluationRunEvent(run.getId()));
        return summary(run);
    }

    @Transactional
    public RunSummary cancel(String runId, long expectedVersion) {
        EvaluationRun run = runs.findByIdForUpdate(runId).orElseThrow(() -> new ResourceNotFoundException("evaluation run not found"));
        run.requestCancellation(expectedVersion);
        if (run.getStatus() == EvaluationRunStatus.QUEUED) state.cancelQueuedInCurrentTransaction(run);
        audit.recordResource("RUN_CANCEL_REQUESTED", "EVALUATION_RUN", runId, "MANUAL", Map.of("status", run.getStatus().name()));
        entityManager.flush();
        return summary(run);
    }

    @Transactional
    public RunSummary retryErrors(String sourceRunId, long expectedVersion) {
        EvaluationRun source = runs.findByIdForUpdate(sourceRunId).orElseThrow(() -> new ResourceNotFoundException("evaluation run not found"));
        List<EvaluationResult> errors = results.findAllByRunIdAndStatusOrderByOrderNoAsc(sourceRunId, EvaluationResultStatus.ERROR);
        if (errors.isEmpty()) throw new ConflictException("任务没有可重试错误");
        source.startErrorRetry(expectedVersion);
        EvaluationTargetSnapshot target = EvaluationRunSnapshotCodec.target(source.getTargetSnapshotJson());
        EvaluationRunParameters parameters = EvaluationRunSnapshotCodec.parameters(source.getParametersJson());
        EvaluationRun retry = runs.save(EvaluationRun.retry(Identities.newId(), source.getId(), source.getDatasetVersionId(), source.getPlatformId(), source.getModelId(),
                source.getPromptId(), target, parameters, errors.size()));
        for (EvaluationResult old : errors) { results.save(EvaluationResult.pending(Identities.newId(), retry.getId(),
                old.getCaseId(), old.getOrderNo(), old.getSnapshotName(), old.getSnapshotCategory(), old.getSnapshotSeverity(), old.getSnapshotInputText(),
                old.getSnapshotExpectedJson(), old.getSnapshotEvaluatorType(), old.getSnapshotContentHash())); }
        audit.recordResource("RUN_ERRORS_RETRIED", "EVALUATION_RUN", retry.getId(), "MANUAL", Map.of("status", retry.getStatus().name(), "itemCount", retry.getTotalCount()));
        events.publishEvent(new EvaluationRunEvent(retry.getId()));
        return summary(retry);
    }

    @Transactional
    public ReviewView review(String runId, String resultId, BigDecimal score, boolean passed, long expectedVersion) {
        state.review(runId, resultId, score, passed, expectedVersion);
        audit.recordResource("RUN_RESULT_REVIEWED", "EVALUATION_RESULT", resultId, "MANUAL", Map.of("status", passed ? "PASSED" : "FAILED"));
        entityManager.flush();
        EvaluationRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("evaluation run not found"));
        EvaluationResult result = results.findById(resultId).orElseThrow(() -> new ResourceNotFoundException("evaluation result not found"));
        return new ReviewView(summary(run), resultView(result));
    }

    @Transactional(readOnly = true)
    public Page<RunSummary> list(Pageable pageable) { return runs.findAllByOrderByCreatedAtDesc(pageable).map(this::summary); }
    @Transactional(readOnly = true)
    public RunSummary get(String id) { return summary(runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("evaluation run not found"))); }
    @Transactional(readOnly = true)
    public List<ResultView> results(String id) { if (runs.findById(id).isEmpty()) throw new ResourceNotFoundException("evaluation run not found"); return results.findAllByRunIdOrderByOrderNoAsc(id).stream().map(this::resultView).toList(); }

    private RunSummary summary(EvaluationRun run) { return new RunSummary(run.getId(),run.getSourceRunId(),run.getStatus(),run.getVersion(),run.getTotalCount(),run.getCompletedCount(),run.getPassedCount(),run.getFailedCount(),run.getErrorCount(),run.getManualReviewCount(),run.getTotalTokens(),run.getTotalCost(),run.getAvgLatencyMs(),run.getP95LatencyMs(),run.isCancelRequested(),run.getStartedAt(),run.getCompletedAt()); }
    private ResultView resultView(EvaluationResult r) { return new ResultView(r.getId(),r.getCaseId(),r.getOrderNo(),r.getSnapshotName(),r.getSnapshotCategory(),r.getSnapshotSeverity(),r.getSnapshotInputText(),r.getSnapshotExpectedJson(),r.getSnapshotEvaluatorType(),r.getActualOutput(),r.getScore(),r.getPassed(),r.isReviewRequired(),r.getReviewedAt(),r.getReviewSummary(),r.getStatus(),r.getErrorCode(),r.getErrorSummary(),r.getTraceSummaryJson(),r.getLatencyMs(),r.getPromptTokens(),r.getCompletionTokens(),r.getTotalTokens(),r.getCost(),r.getAttemptCount(),r.getVersion()); }

    public record CreateRunCommand(String datasetVersionId,String platformId,String modelId,String promptId,BigDecimal temperature,Integer maxTokens,Integer timeoutMs) {}
    public record RunSummary(String id,String sourceRunId,EvaluationRunStatus status,long version,int totalCount,int completedCount,int passedCount,int failedCount,int errorCount,int manualReviewCount,long totalTokens,BigDecimal totalCost,long avgLatencyMs,long p95LatencyMs,boolean cancelRequested,java.time.Instant startedAt,java.time.Instant completedAt) {}
    public record ResultView(String id,String caseId,int orderNo,String snapshotName,String snapshotCategory,String snapshotSeverity,String input,String expectedJson,String evaluatorType,String actualOutput,BigDecimal score,Boolean passed,boolean reviewRequired,java.time.Instant reviewedAt,String reviewSummary,EvaluationResultStatus status,String errorCode,String errorSummary,String traceSummaryJson,long latencyMs,long promptTokens,long completionTokens,long totalTokens,BigDecimal cost,int attemptCount,long version) { @Override public String toString() { return "ResultView[id="+id+", input=[redacted], expectedJson=[redacted], actualOutput=[redacted]]"; } }
    public record ReviewView(RunSummary run, ResultView result) {}
}
