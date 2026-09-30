package com.yiwei.midplat.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.common.api.ConflictException;
import com.yiwei.midplat.common.api.ForbiddenException;
import com.yiwei.midplat.common.api.ResourceNotFoundException;
import com.yiwei.midplat.common.domain.Identities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformApiService {

    private static final TypeReference<List<PlatformApiItem>> LIST = new TypeReference<>() {};
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ManagedPlatformRepository repository;
    private final ObjectMapper objectMapper;

    PlatformApiService(ManagedPlatformRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PlatformApiItem> list(String platformId) {
        return resolve(require(platformId));
    }

    @Transactional(readOnly = true)
    public ResolvedApi requireCallable(String ownerPlatformId, String apiId, String callerPlatformId) {
        ManagedPlatform owner = require(ownerPlatformId);
        PlatformApiItem item = resolve(owner).stream()
                .filter(candidate -> candidate.id().equals(apiId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("api not found: " + apiId));
        boolean self = owner.getId().equals(callerPlatformId);
        if (!self && !item.external()) {
            throw new ForbiddenException("该接口未对其他项目开放");
        }
        return new ResolvedApi(owner, item);
    }

    @Transactional
    public List<PlatformApiItem> toggle(String platformId, String apiId, boolean external) {
        ManagedPlatform platform = require(platformId);
        List<PlatformApiItem> items = resolve(platform).stream()
                .map(item -> item.id().equals(apiId) ? item.withExternal(external) : item)
                .toList();
        if (items.stream().noneMatch(item -> item.id().equals(apiId))) {
            throw new ResourceNotFoundException("api not found: " + apiId);
        }
        save(platform, items, new LinkedHashSet<>(readStored(platform).removedIds()));
        return items;
    }

    @Transactional
    public List<PlatformApiItem> create(String platformId, ApiWriteCmd cmd) {
        ManagedPlatform platform = require(platformId);
        StoredCatalog stored = readStored(platform);
        List<PlatformApiItem> current = resolve(platform);
        String id = cmd.id() == null || cmd.id().isBlank()
                ? "api-" + Identities.newId().substring(0, 12)
                : cmd.id().trim();
        if (current.stream().anyMatch(item -> item.id().equals(id))) {
            throw new ConflictException("接口 ID 已存在: " + id);
        }
        PlatformApiItem created = normalize(new PlatformApiItem(id, cmd.name(), cmd.method(), cmd.path(), cmd.note(), cmd.external()));
        List<PlatformApiItem> next = new ArrayList<>(current);
        next.add(created);
        Set<String> removed = new LinkedHashSet<>(stored.removedIds());
        removed.remove(id);
        save(platform, next, removed);
        return next;
    }

    @Transactional
    public List<PlatformApiItem> update(String platformId, String apiId, ApiWriteCmd cmd) {
        ManagedPlatform platform = require(platformId);
        List<PlatformApiItem> current = resolve(platform);
        if (current.stream().noneMatch(item -> item.id().equals(apiId))) {
            throw new ResourceNotFoundException("api not found: " + apiId);
        }
        List<PlatformApiItem> next = current.stream()
                .map(item -> item.id().equals(apiId)
                        ? normalize(item.withDetails(cmd.name(), cmd.method(), cmd.path(), cmd.note(), cmd.external()))
                        : item)
                .toList();
        save(platform, next, new LinkedHashSet<>(readStored(platform).removedIds()));
        return next;
    }

    @Transactional
    public List<PlatformApiItem> delete(String platformId, String apiId) {
        ManagedPlatform platform = require(platformId);
        List<PlatformApiItem> current = resolve(platform);
        if (current.stream().noneMatch(item -> item.id().equals(apiId))) {
            throw new ResourceNotFoundException("api not found: " + apiId);
        }
        List<PlatformApiItem> next = current.stream().filter(item -> !item.id().equals(apiId)).toList();
        Set<String> removed = new LinkedHashSet<>(readStored(platform).removedIds());
        removed.add(apiId);
        save(platform, next, removed);
        return next;
    }

    private List<PlatformApiItem> resolve(ManagedPlatform platform) {
        List<PlatformApiItem> catalog = PlatformApiCatalog.forPlatform(platform);
        StoredCatalog stored = readStored(platform);
        Map<String, PlatformApiItem> byId = new LinkedHashMap<>();
        for (PlatformApiItem item : catalog) {
            if (!stored.removedIds().contains(item.id())) {
                byId.put(item.id(), item);
            }
        }
        for (PlatformApiItem item : stored.items()) {
            if (!stored.removedIds().contains(item.id())) {
                byId.put(item.id(), item);
            }
        }
        return List.copyOf(byId.values());
    }

    private StoredCatalog readStored(ManagedPlatform platform) {
        if (platform.getApisJson() == null || platform.getApisJson().isBlank()) {
            return new StoredCatalog(List.of(), Set.of());
        }
        try {
            JsonNode root = objectMapper.readTree(platform.getApisJson());
            if (root.isArray()) {
                return new StoredCatalog(objectMapper.convertValue(root, LIST), Set.of());
            }
            List<PlatformApiItem> items = root.has("items")
                    ? objectMapper.convertValue(root.get("items"), LIST)
                    : List.of();
            Set<String> removed = new LinkedHashSet<>();
            if (root.has("removedIds") && root.get("removedIds").isArray()) {
                root.get("removedIds").forEach(node -> {
                    if (node.isTextual()) {
                        removed.add(node.asText());
                    }
                });
            }
            return new StoredCatalog(items == null ? List.of() : items, removed);
        } catch (Exception ex) {
            return new StoredCatalog(List.of(), Set.of());
        }
    }

    private void save(ManagedPlatform platform, List<PlatformApiItem> items, Set<String> removedIds) {
        try {
            platform.updateApisJson(objectMapper.writeValueAsString(new StoredCatalog(items, List.copyOf(removedIds))));
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法保存接口目录");
        }
    }

    private static PlatformApiItem normalize(PlatformApiItem item) {
        String method = item.method() == null ? "" : item.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("请求方法只支持 GET、POST、PUT、PATCH、DELETE");
        }
        String path = item.path() == null ? "" : item.path().trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("接口路径必须以 / 开头");
        }
        String name = item.name() == null ? "" : item.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("接口名称不能为空");
        }
        String id = item.id() == null ? "" : item.id().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("接口 ID 不能为空");
        }
        String note = item.note() == null ? "" : item.note().trim();
        return new PlatformApiItem(id, name, method, path, note, item.external());
    }

    private ManagedPlatform require(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("platform not found: " + id));
    }

    public record ResolvedApi(ManagedPlatform owner, PlatformApiItem item) {}

    public record ApiWriteCmd(String id, String name, String method, String path, String note, boolean external) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredCatalog(List<PlatformApiItem> items, List<String> removedIds) {
        StoredCatalog {
            items = items == null ? List.of() : items;
            removedIds = removedIds == null ? List.of() : removedIds;
        }

        StoredCatalog(List<PlatformApiItem> items, Set<String> removedIds) {
            this(items, List.copyOf(removedIds));
        }

    }
}
