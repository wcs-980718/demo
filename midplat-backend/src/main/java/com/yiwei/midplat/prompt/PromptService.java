package com.yiwei.midplat.prompt;

import com.yiwei.midplat.capability.CapabilityService;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.fusion.FusionCatalog;
import com.yiwei.midplat.fusion.FusionOwnership;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import com.yiwei.midplat.evaluation.run.EvaluationRunRepository;
import com.yiwei.midplat.platform.ManagedPlatformRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptService {

    private static final Set<String> SLOTS = Set.of("rag-qa", "prelabel", "answer", "app", "agent-role");

    private final FusionOwnership fusionOwnership;
    private final FusionCatalog catalog;
    private final PromptRepository repository;
    private final ManagedPlatformRepository platforms;
    private final EvaluationRunRepository evaluationRuns;
    private final CapabilityService capabilities;

    PromptService(PromptRepository repository, ManagedPlatformRepository platforms, EvaluationRunRepository evaluationRuns, FusionCatalog catalog, FusionOwnership fusionOwnership, CapabilityService capabilities) {
        this.fusionOwnership = fusionOwnership;
        this.catalog = catalog;
        this.repository = repository;
        this.platforms = platforms;
        this.evaluationRuns = evaluationRuns;
        this.capabilities = capabilities;
    }

    @Transactional
    public void captureCatalogRevisions() { repository.findAll().forEach(catalog::capture); }

    @Transactional(readOnly = true)
    public List<PromptView> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional
    public PromptView create(CreatePromptCmd cmd) {
        if (!SLOTS.contains(cmd.slot())) {
            throw new IllegalArgumentException("不支持的提示词槽位：" + cmd.slot());
        }
        var entity = new Prompt(Identities.newId(), cmd.name(), cmd.slot(), cmd.versionName(), cmd.body());
        repository.saveAndFlush(entity);
        catalog.capture(entity);
        capabilities.register("prompt", "prompt", entity.getId(), entity.getName());
        return toView(entity);
    }

    @Transactional
    public PromptView update(String id, UpdatePromptCmd cmd) {
        var entity = require(id);
        catalog.capture(entity);
        entity.update(cmd.name(), cmd.versionName(), cmd.body());
        catalog.capture(entity);
        capabilities.register("prompt", "prompt", entity.getId(), entity.getName());
        return toView(entity);
    }

    @Transactional
    public void delete(String id) {
        if (fusionOwnership.references("prompt", id)) throw new ConflictException("该资源仍被智能体配置发布引用，无法删除");
        var entity = require(id);
        if (platforms.existsBoundToPrompt(id)) {
            throw new ConflictException("该提示词仍被项目绑定，无法删除");
        }
        if (evaluationRuns.existsByPromptId(id)) {
            throw new ConflictException("该提示词仍被评测任务引用，无法删除");
        }
        repository.delete(entity);
    }

    public String requireBody(String id) {
        return require(id).getBody();
    }

    /** Returns a detached, immutable prompt snapshot for an evaluation run. */
    @Transactional(readOnly = true)
    public EvaluationPromptSnapshot requireEvaluationSnapshot(String id) {
        Prompt prompt = require(id);
        String body = prompt.getBody();
        return new EvaluationPromptSnapshot(prompt.getId(), prompt.getName(), prompt.getVersionName(), body, sha256(body));
    }

    private Prompt require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("prompt not found: " + id));
    }

    private PromptView toView(Prompt entity) {
        return new PromptView(entity.getId(), entity.getName(), entity.getSlot(), entity.getVersionName(), entity.getBody());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record CreatePromptCmd(String name, String slot, String versionName, String body) {
        @Override public String toString() { return "CreatePromptCmd[name=" + name + ", slot=" + slot + ", versionName=" + versionName + ", body=[redacted]]"; }
    }
    public record UpdatePromptCmd(String name, String versionName, String body) {
        @Override public String toString() { return "UpdatePromptCmd[name=" + name + ", versionName=" + versionName + ", body=[redacted]]"; }
    }
    public record PromptView(String id, String name, String slot, String version, String body) {
        @Override public String toString() { return "PromptView[id=" + id + ", name=" + name + ", slot=" + slot + ", version=" + version + ", body=[redacted]]"; }
    }
    public record EvaluationPromptSnapshot(String id, String name, String version, String body, String bodySha256) {
        @Override
        public String toString() {
            return "EvaluationPromptSnapshot[id=" + id + ", name=" + name + ", version=" + version
                    + ", body=[redacted], bodySha256=" + bodySha256 + "]";
        }
    }
}
