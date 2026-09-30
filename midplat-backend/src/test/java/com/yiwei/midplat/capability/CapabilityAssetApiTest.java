package com.yiwei.midplat.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 能力资产管理闭环集成测试（W5）。管理会话使用与浏览器一致的内网融合会话；
 * 智能体通道固定指向不可达的环回端口 1，用于验证真实失败路径：同步必须记录 FAILED，
 * 绝不把上游失败降级为“成功”。
 */
@SpringBootTest(properties = {
        "midplat.fusion.enabled=true",
        "midplat.fusion.intranet-management=true",
        "midplat.fusion.service-token=0123456789012345678901234567890123456789",
        "midplat.fusion.web-origins=http://127.0.0.1:8000",
        "midplat.fusion.agent-url=http://127.0.0.1:1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class CapabilityAssetApiTest {
    private static final String TOOL_CONTENT = """
            {"toolName":"get_weather","description":"查询指定城市天气","url":"https://203.0.113.10/api/weather",\
            "method":"GET","readOnly":true,"parameters":{"type":"object","properties":{"city":{"type":"string"}}}}""";

    private final ObjectMapper json = JsonMapper.builder().build();
    @Autowired
    private MockMvc mockMvc;
    private MockHttpSession session;
    private String csrf;

    @BeforeEach
    void establishAdminSession() throws Exception {
        session = new MockHttpSession();
        mockMvc.perform(get("/api/fusion/session").session(session)).andExpect(status().isOk());
        csrf = (String) session.getAttribute("fusion.csrf");
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.session(session).header("X-Fusion-CSRF", csrf);
    }

    private JsonNode createAsset(String kind, String name, String contentJson) throws Exception {
        String response = mockMvc.perform(admin(post("/api/capabilities/assets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"%s\",\"name\":\"%s\",\"description\":\"测试资产\",\"content\":%s}"
                                .formatted(kind, name, contentJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", org.hamcrest.Matchers.is(true)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).at("/data");
    }

    private String createProject() throws Exception {
        String response = mockMvc.perform(admin(post("/api/platforms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"资产测试项目\",\"icon\":\"Library\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 触发 JPA 自动 flush，使测试事务内新建平台对后续 JdbcTemplate 查询可见
        // （生产中两个请求处于各自独立事务，提交后天然可见，不存在此交错问题）。
        mockMvc.perform(get("/api/platforms").session(session)).andExpect(status().isOk());
        return json.readTree(response).at("/data/id").asText();
    }

    @Test
    void adminCreatesAllFourKindsWhileAnonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/capabilities/assets"))
                .andExpect(status().isUnauthorized());

        JsonNode skill = createAsset("skill", "测试技能", "{\"body\":\"回答时遵循科室口径\"}");
        assertEquals("skill", skill.get("kind").asText());
        assertEquals("DRAFT", skill.get("status").asText());
        assertEquals(1, skill.get("revision").asLong());
        assertTrue(skill.get("content").get("body").asText().contains("科室"), skill.toString());

        JsonNode tool = createAsset("tool", "天气工具", TOOL_CONTENT);
        assertEquals("get_weather", tool.get("content").get("toolName").asText());

        JsonNode knowledge = createAsset("knowledge", "知识库", "{\"documents\":[{\"title\":\"就诊须知\",\"text\":\"门诊开诊时间8:00-17:00\"}]}");
        assertEquals(1, knowledge.get("content").get("documents").size());

        JsonNode data = createAsset("data", "数据源", "{\"documents\":[{\"title\":\"指标口径\",\"text\":\"门诊人次按挂号口径统计\"}]}");
        assertEquals("data", data.get("kind").asText());

        // 目录头以 cap-asset-<assetId> 稳定登记，正文不复制进模型/提示词目录。
        String catalog = mockMvc.perform(get("/api/capabilities").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(catalog.contains("cap-asset-" + skill.get("id").asText()));
        assertTrue(catalog.contains("cap-asset-" + tool.get("id").asText()));

        // 列表分页返回修订、hash、授权数量与同步摘要，不默认暴露正文。
        mockMvc.perform(get("/api/capabilities/assets").session(session).param("kind", "skill").param("page", "0").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.totalPages", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.content[0].id").exists())
                .andExpect(jsonPath("$.data.content[0].content").doesNotExist());
    }

    @Test
    void unsafeToolAndDocumentContentIsRejected() throws Exception {
        rejected("skill", "{}");
        rejected("tool", "{\"toolName\":\"get_weather\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"},\"headers\":{\"Authorization\":\"Bearer secret\"}}");
        rejected("tool", "{\"toolName\":\"get_weather\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"},\"apiKey\":\"sk-123\"}");
        rejected("tool", "{\"toolName\":\"local\",\"description\":\"d\",\"url\":\"http://127.0.0.1:8080/tool\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"}}");
        rejected("tool", "{\"toolName\":\"meta\",\"description\":\"d\",\"url\":\"http://169.254.169.254/latest/meta-data\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"}}");
        rejected("tool", "{\"toolName\":\"query\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a?token=x\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"}}");
        rejected("tool", "{\"toolName\":\"writer\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a\",\"method\":\"POST\",\"readOnly\":false,\"parameters\":{\"type\":\"object\"}}");
        rejected("tool", "{\"toolName\":\"1bad\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"}}");
        rejected("tool", "{\"toolName\":\"extra\",\"description\":\"d\",\"url\":\"https://203.0.113.10/a\",\"method\":\"GET\",\"readOnly\":true,\"parameters\":{\"type\":\"object\"},\"timeoutSeconds\":5}");
        rejected("knowledge", "{\"documents\":[]}");
        String twoHundredOne = IntStream.rangeClosed(1, 201)
                .mapToObj(i -> "{\"title\":\"t" + i + "\",\"text\":\"x\"}").collect(Collectors.joining(",", "[", "]"));
        rejected("knowledge", "{\"documents\":" + twoHundredOne + "}");
        rejected("data", "{\"documents\":[{\"title\":\"只有标题\"}]}");
        rejected("mcp", "{\"body\":\"x\"}");
    }

    private void rejected(String kind, String contentJson) throws Exception {
        mockMvc.perform(admin(post("/api/capabilities/assets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"%s\",\"name\":\"非法内容\",\"content\":%s}".formatted(kind, contentJson)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateUsesCasAndKeepsOldRevisionImmutable() throws Exception {
        JsonNode created = createAsset("skill", "CAS技能", "{\"body\":\"v1\"}");
        String id = created.get("id").asText();
        String firstHash = created.get("hash").asText();
        String firstRevisionId = created.get("currentRevisionId").asText();

        mockMvc.perform(admin(put("/api/capabilities/assets/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"note\":\"改为v2\",\"content\":{\"body\":\"v2\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.data.content.body", org.hamcrest.Matchers.is("v2")));

        // 错误 expectedRevision 必须 409，且不能产生第三个修订。
        mockMvc.perform(admin(put("/api/capabilities/assets/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"content\":{\"body\":\"冲突\"}}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin(put("/api/capabilities/assets/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":{\"body\":\"缺少CAS\"}}"))
                .andExpect(status().isUnprocessableEntity());

        String revisions = mockMvc.perform(get("/api/capabilities/assets/" + id + "/revisions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(2)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode rows = json.readTree(revisions).at("/data");
        JsonNode old = null;
        for (JsonNode row : rows) if (row.get("revision").asLong() == 1) old = row;
        assertEquals(firstHash, old.get("hash").asText());
        assertEquals(firstRevisionId, old.get("id").asText());
        assertNotEquals(firstHash, json.readTree(mockMvc.perform(get("/api/capabilities/assets/" + id).session(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).at("/data/hash").asText());
    }

    @Test
    void statusGrantsAndSyncValidation() throws Exception {
        JsonNode asset = createAsset("skill", "授权技能", "{\"body\":\"x\"}");
        String id = asset.get("id").asText();
        String revisionId = asset.get("currentRevisionId").asText();
        String project = createProject();

        mockMvc.perform(admin(patch("/api/capabilities/assets/" + id + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 项目不存在 → 404。
        mockMvc.perform(admin(put("/api/capabilities/projects/plat-missing-xyz/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[]}"))
                .andExpect(status().isNotFound());

        // 草稿资产可授权，但不镜像：sync 摘要必须为空。
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"current\"}]}"
                                .formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sync", org.hamcrest.Matchers.empty()));

        mockMvc.perform(admin(patch("/api/capabilities/assets/" + id + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", org.hamcrest.Matchers.is("ACTIVE")));

        // 跨资产 pinned 修订被拒；pinned 缺修订、非法规则、空 operation 均 422。
        JsonNode other = createAsset("skill", "别的技能", "{\"body\":\"y\"}");
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"pinned\",\"revisionId\":\"%s\"}]}"
                                .formatted(id, other.get("currentRevisionId").asText())))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"pinned\"}]}".formatted(id)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"latest\"}]}".formatted(id)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"\",\"versionRule\":\"current\"}]}".formatted(id)))
                .andExpect(status().isUnprocessableEntity());

        // 有效 pinned 授权：上游不可达时同步记录 FAILED 而不是伪造 SYNCED。
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":1,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"pinned\",\"revisionId\":\"%s\"}]}"
                                .formatted(id, revisionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyRevision", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.data.sync[0].status", org.hamcrest.Matchers.is("FAILED")))
                .andExpect(jsonPath("$.data.sync[0].error").isNotEmpty());

        // 项目授权策略乐观锁：过期 policyRevision 被拒。
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/capabilities/projects/" + project + "/asset-grants").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyRevision", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.data.grants[0].assetId", org.hamcrest.Matchers.is(id)))
                .andExpect(jsonPath("$.data.grants[0].versionRule", org.hamcrest.Matchers.is("pinned")));

        // 详情包含 FAILED 同步记录，本地授权仍可审查。
        mockMvc.perform(get("/api/capabilities/assets/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sync[0].status", org.hamcrest.Matchers.is("FAILED")))
                .andExpect(jsonPath("$.data.grants[0].projectId", org.hamcrest.Matchers.is(project)));

        // pinned 授权不因新修订漂移：更新资产后不产生新的镜像同步。
        mockMvc.perform(admin(put("/api/capabilities/assets/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"content\":{\"body\":\"z\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sync", org.hamcrest.Matchers.empty()));

        // 归档为终态；归档资产不可再授权。
        mockMvc.perform(admin(patch("/api/capabilities/assets/" + id + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", org.hamcrest.Matchers.is("ARCHIVED")))
                .andExpect(jsonPath("$.data.sync[0].status", org.hamcrest.Matchers.is("FAILED")));
        mockMvc.perform(admin(patch("/api/capabilities/assets/" + id + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":1,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"current\"}]}".formatted(id)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void currentGrantKeepsNullRevisionAndEmptyPolicyDoesNotReset() throws Exception {
        JsonNode asset = createAsset("skill", "跟随最新技能", "{\"body\":\"v1\"}");
        String id = asset.get("id").asText();
        String project = createProject();
        mockMvc.perform(admin(patch("/api/capabilities/assets/" + id + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":0,\"grants\":[{\"assetId\":\"%s\",\"operation\":\"use\",\"versionRule\":\"current\"}]}".formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyRevision", org.hamcrest.Matchers.is(1)))
                .andExpect(jsonPath("$.data.grants[0].versionRule", org.hamcrest.Matchers.is("current")))
                .andExpect(jsonPath("$.data.grants[0].revisionId").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(admin(put("/api/capabilities/assets/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"content\":{\"body\":\"v2\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sync[0].status", org.hamcrest.Matchers.is("FAILED")));

        mockMvc.perform(get("/api/capabilities/projects/" + project + "/asset-grants").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants[0].revisionId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.grants[0].versionRule", org.hamcrest.Matchers.is("current")));

        mockMvc.perform(admin(post("/api/capabilities/assets/" + id + "/sync")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sync[0].status", org.hamcrest.Matchers.is("FAILED")));

        mockMvc.perform(admin(put("/api/capabilities/projects/" + project + "/asset-grants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyRevision\":1,\"grants\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyRevision", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.data.grants", org.hamcrest.Matchers.empty()));

        mockMvc.perform(get("/api/capabilities/projects/" + project + "/asset-grants").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyRevision", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.data.grants", org.hamcrest.Matchers.empty()));
    }

    @Test
    void allPageIncludesCatalogSummariesWithoutCopyingBodies() throws Exception {
        createAsset("skill", "目录技能", "{\"body\":\"不要出现在列表正文\"}");
        mockMvc.perform(admin(post("/api/models"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"目录模型\",\"kind\":\"llm\",\"model\":\"catalog-model\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"apiKey\":\"k-catalog-key-xxxxxxxx\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(admin(post("/api/prompts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"目录提示词\",\"slot\":\"agent-role\",\"body\":\"提示词正文不应出现在能力资产列表\"}"))
                .andExpect(status().isCreated());

        String page = mockMvc.perform(get("/api/capabilities/assets").session(session).param("kind", "all").param("page", "0").param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.kind=='skill')].name", org.hamcrest.Matchers.hasItem("目录技能")))
                .andExpect(jsonPath("$.data.content[?(@.kind=='model')].name", org.hamcrest.Matchers.hasItem("目录模型")))
                .andExpect(jsonPath("$.data.content[?(@.kind=='prompt')].name", org.hamcrest.Matchers.hasItem("目录提示词")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode content = json.readTree(page).at("/data/content");
        for (JsonNode row : content) {
            org.junit.jupiter.api.Assertions.assertFalse(row.has("content"), row.toString());
            org.junit.jupiter.api.Assertions.assertFalse(row.toString().contains("不要出现在列表正文"));
            org.junit.jupiter.api.Assertions.assertFalse(row.toString().contains("提示词正文不应出现在能力资产列表"));
        }
    }

    @Test
    void fusionSaveAssetObjectReplyExposesRevisionId() {
        JsonNode object = json.createObjectNode().put("id", "ca-1").put("revisionId", "rev-9");
        assertEquals("rev-9", CapabilityAssetService.fusionRevisionId(object, "ca-1"));
        JsonNode array = json.createArrayNode().add(json.createObjectNode().put("id", "ca-1").put("revisionId", "rev-8"));
        assertEquals("rev-8", CapabilityAssetService.fusionRevisionId(array, "ca-1"));
        org.junit.jupiter.api.Assertions.assertNull(CapabilityAssetService.fusionRevisionId(object, "ca-other"));
    }
}
