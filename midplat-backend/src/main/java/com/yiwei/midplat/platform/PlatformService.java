package com.yiwei.midplat.platform;

import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.fusion.FusionOwnership;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.gate.EvaluationGateService;
import com.yiwei.midplat.evaluation.run.EvaluationRunRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformService {

    private final FusionOwnership fusionOwnership;
    private final ManagedPlatformRepository repository;
    private final PlatformRuntimeService runtimeService;
    private final EvaluationRunRepository evaluationRuns;
    private final EvaluationGateService gateService;

    PlatformService(
            ManagedPlatformRepository repository,
            PlatformRuntimeService runtimeService,
            EvaluationRunRepository evaluationRuns,
            EvaluationGateService gateService,
            FusionOwnership fusionOwnership) {
        this.fusionOwnership = fusionOwnership;
        this.repository = repository;
        this.runtimeService = runtimeService;
        this.evaluationRuns = evaluationRuns;
        this.gateService = gateService;
    }

    @Transactional
    public List<PlatformView> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional
    public PlatformView get(String id) {
        return toView(require(id));
    }

    /** Credential-free platform identity for immutable evaluation snapshots. */
    @Transactional(readOnly = true)
    public EvaluationPlatformSnapshot requireEvaluationSnapshot(String id) {
        ManagedPlatform entity = require(id);
        return new EvaluationPlatformSnapshot(entity.getId(), entity.getName(), Long.toString(entity.getVersion()),
                entity.getEntryUrl(), entity.isConsume());
    }

    @Transactional
    public PlatformView create(CreatePlatformCmd cmd) {
        // W1: project registration no longer mints a credential. Client/Credential issue via /api/access.
        var entity = new ManagedPlatform(Identities.newId(), cmd.name(), cmd.entryUrl(), cmd.icon(), true, null);
        entity.update(cmd.name(), cmd.entryUrl(), cmd.icon(), cmd.llmModelId(), cmd.embeddingModelId(), cmd.rerankModelId(), cmd.promptId());
        var saved = repository.saveAndFlush(entity);
        return toView(saved);
    }

    @Transactional
    public PlatformView update(String id, UpdatePlatformCmd cmd) {
        var entity = require(id);
        boolean fused = fusionOwnership.manages(id);
        if (fused && (!Objects.equals(entity.getLlmModelId(), blank(cmd.llmModelId()))
                || !Objects.equals(entity.getEmbeddingModelId(), blank(cmd.embeddingModelId()))
                || !Objects.equals(entity.getRerankModelId(), blank(cmd.rerankModelId()))
                || !Objects.equals(entity.getPromptId(), blank(cmd.promptId())))) {
            fusionOwnership.requireLegacyWritable(id);
        }
        entity.update(cmd.name(), cmd.entryUrl(), cmd.icon(), cmd.llmModelId(), cmd.embeddingModelId(), cmd.rerankModelId(), cmd.promptId());
        if (!fused) runtimeService.push(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(String id) {
        var entity = require(id);
        if (evaluationRuns.existsByPlatformId(id)) {
            throw new ConflictException("该平台仍被评测任务引用，无法删除");
        }
        if (gateService.referencesPlatform(id)) {
            throw new ConflictException("该平台仍被门禁策略引用，无法删除");
        }
        repository.delete(entity);
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }

    private ManagedPlatform require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("platform not found: " + id));
    }

    private PlatformView toView(ManagedPlatform entity) {
        // W1: token is no longer returned by list/get; existing projects rely on /api/access credentials.
        return new PlatformView(
                entity.getId(),
                entity.getName(),
                entity.getEntryUrl(),
                entity.getIcon(),
                entity.isConsume(),
                null,
                entity.getLlmModelId(),
                entity.getEmbeddingModelId(),
                entity.getRerankModelId(),
                entity.getPromptId(),
                entity.getAgentMode());
    }

    public record CreatePlatformCmd(String name, String entryUrl, String icon, String llmModelId, String embeddingModelId, String rerankModelId, String promptId) {}
    public record UpdatePlatformCmd(String name, String entryUrl, String icon, String llmModelId, String embeddingModelId, String rerankModelId, String promptId) {}
    public record PlatformView(String id, String name, String entryUrl, String icon, boolean consume, String token, String llmModelId, String embeddingModelId, String rerankModelId, String promptId, String agentMode) {}
    public record EvaluationPlatformSnapshot(String id, String name, String version, String entryUrl, boolean consume) {}
}
