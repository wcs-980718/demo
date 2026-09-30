package com.yiwei.midplat.model;

import com.yiwei.midplat.capability.CapabilityService;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.fusion.FusionCatalog;
import com.yiwei.midplat.fusion.FusionOwnership;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.run.EvaluationRunRepository;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelService {

    private final FusionOwnership fusionOwnership;
    private final FusionCatalog catalog;
    private final AiModelRepository repository;
    private final ModelUpstreamClient upstream;
    private final ManagedPlatformRepository platforms;
    private final EvaluationRunRepository evaluationRuns;
    private final CapabilityService capabilities;

    ModelService(
            AiModelRepository repository,
            ModelUpstreamClient upstream,
            ManagedPlatformRepository platforms,
            EvaluationRunRepository evaluationRuns, FusionCatalog catalog, FusionOwnership fusionOwnership,
            CapabilityService capabilities) {
        this.fusionOwnership = fusionOwnership;
        this.catalog = catalog;
        this.repository = repository;
        this.upstream = upstream;
        this.platforms = platforms;
        this.evaluationRuns = evaluationRuns;
        this.capabilities = capabilities;
    }

    @Transactional
    public void captureCatalogRevisions() { repository.findAll().forEach(catalog::capture); }

    @Transactional(readOnly = true)
    public List<ModelView> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional
    public ModelView create(CreateModelCmd cmd) {
        var entity = new AiModel(Identities.newId(), cmd.name(), cmd.kind(), cmd.modelName(), cmd.baseUrl(), cmd.apiKey(),
                cmd.inputPricePerMillion(), cmd.outputPricePerMillion());
        if (cmd.capabilities() != null) entity.updateCapabilities(cmd.capabilities().toolCalls(), cmd.capabilities().jsonObject());
        repository.saveAndFlush(entity);
        catalog.capture(entity);
        capabilities.register("model", "model", entity.getId(), entity.getName());
        return toView(entity);
    }

    @Transactional
    public ModelView update(String id, CreateModelCmd cmd) {
        var entity = require(id);
        catalog.capture(entity);
        entity.update(cmd.name(), cmd.kind(), cmd.modelName(), cmd.baseUrl(), cmd.apiKey(),
                cmd.inputPricePerMillion(), cmd.outputPricePerMillion());
        if (cmd.capabilities() != null) entity.updateCapabilities(cmd.capabilities().toolCalls(), cmd.capabilities().jsonObject());
        catalog.capture(entity);
        // 目录头登记必须在新名称与最新修订落库之后（与 PromptService 对齐）。
        capabilities.register("model", "model", entity.getId(), entity.getName());
        return toView(entity);
    }

    @Transactional
    public void delete(String id) {
        if (fusionOwnership.references("model", id)) throw new ConflictException("该资源仍被智能体配置发布引用，无法删除");
        var entity = require(id);
        if (evaluationRuns.existsByModelId(id)) {
            throw new ConflictException("该模型仍被评测任务引用，无法删除");
        }
        if (platforms.existsBoundToModel(id)) {
            throw new ConflictException("该模型仍被项目绑定，无法删除");
        }
        repository.delete(entity);
    }

    @Transactional
    public ModelView ping(String id) {
        var entity = require(id);
        boolean ok = upstream.ping(entity);
        entity.markPing(ok ? "ok" : "fail");
        return toView(entity);
    }

    AiModel requireModel(String id) {
        return require(id);
    }

    public ModelUpstreamClient.ChatResult chat(String id, String systemPrompt, String userInput) {
        return upstream.chat(requireRuntimeConfiguration(id), systemPrompt, userInput);
    }

    public ModelUpstreamClient.EmbedResult embed(String id, String input) {
        return upstream.embed(requireRuntimeConfiguration(id), input);
    }

    public ModelUpstreamClient.RerankResult rerank(String id, String query, List<String> documents) {
        return upstream.rerank(requireRuntimeConfiguration(id), query, documents);
    }

    public void putRuntimeSettings(Map<String, Object> body, String prefix, String id) {
        ModelRuntimeConfiguration model = requireRuntimeConfiguration(id);
        body.put(prefix + "Model", model.modelName);
        body.put(prefix + "BaseUrl", model.baseUrl);
        if (model.apiKey != null && !model.apiKey.isBlank()) body.put(prefix + "ApiKey", model.apiKey);
    }

    private ModelRuntimeConfiguration requireRuntimeConfiguration(String id) {
        AiModel entity = require(id);
        return new ModelRuntimeConfiguration(entity.getKind(), entity.getModelName(), entity.getBaseUrl(), entity.getApiKey());
    }

    /** A credential-free, immutable boundary for evaluation snapshots. */
    @Transactional(readOnly = true)
    public EvaluationModelSnapshot requireEvaluationSnapshot(String id) {
        AiModel entity = require(id);
        return new EvaluationModelSnapshot(entity.getId(), entity.getName(), entity.getKind(), entity.getModelName(), entity.getBaseUrl(),
                entity.getInputPricePerMillion(), entity.getOutputPricePerMillion());
    }

    /** Reads the current credential only when an evaluator is about to make an external request. */
    @Transactional(readOnly = true)
    public String requireCurrentApiKey(String id) {
        return require(id).getApiKey();
    }

    private AiModel require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("model not found: " + id));
    }

    private ModelView toView(AiModel entity) {
        return new ModelView(entity.getId(), entity.getName(), entity.getKind().name(), entity.getModelName(), entity.getBaseUrl(),
                entity.getPingStatus(), entity.getInputPricePerMillion(), entity.getOutputPricePerMillion(), new ModelCapabilities(entity.getSupportsToolCalls(), entity.getSupportsJsonObject()));
    }

    public record CreateModelCmd(String name, ModelKind kind, String modelName, String baseUrl, String apiKey,
            BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, ModelCapabilities capabilities) {
        public CreateModelCmd(String name, ModelKind kind, String modelName, String baseUrl, String apiKey, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
            this(name,kind,modelName,baseUrl,apiKey,inputPricePerMillion,outputPricePerMillion,null);
        }
    }
    public record ModelCapabilities(Boolean toolCalls, Boolean jsonObject) {}
    public record ModelView(String id, String name, String kind, String model, String baseUrl, String pingStatus,
            BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, ModelCapabilities capabilities) {
        public ModelView(String id, String name, String kind, String model, String baseUrl, String pingStatus, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
            this(id,name,kind,model,baseUrl,pingStatus,inputPricePerMillion,outputPricePerMillion,null);
        }
    }
    public record EvaluationModelSnapshot(String id, String name, ModelKind kind, String modelName, String baseUrl,
            BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {}
    static final class ModelRuntimeConfiguration {
        private final ModelKind kind;
        private final String modelName;
        private final String baseUrl;
        private final String apiKey;

        ModelRuntimeConfiguration(ModelKind kind, String modelName, String baseUrl, String apiKey) {
            this.kind = kind;
            this.modelName = modelName;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        @Override
        public String toString() {
            return "ModelRuntimeConfiguration[kind=" + kind + ", modelName=" + modelName + ", baseUrl=" + baseUrl + ", apiKey=[redacted]]";
        }

        ModelKind kind() { return kind; }
        String modelName() { return modelName; }
        String baseUrl() { return baseUrl; }
        String apiKey() { return apiKey; }
    }
}
