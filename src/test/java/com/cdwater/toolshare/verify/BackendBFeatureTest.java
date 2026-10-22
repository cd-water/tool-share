package com.cdwater.toolshare.verify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.notify.entity.Message;
import com.cdwater.toolshare.notify.mapper.MessageMapper;
import com.cdwater.toolshare.rental.service.RentalService;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.repair.entity.RepairOrder;
import com.cdwater.toolshare.repair.mapper.RepairOrderMapper;
import com.cdwater.toolshare.tool.service.ToolService;
import com.cdwater.toolshare.user.entity.User;
import com.cdwater.toolshare.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后端 B 功能级验证：repair / notify / user / ai。
 * A 的 RentalService、ToolService 尚未实现，这里用 mock 隔离，只测 B 自身逻辑。
 *
 * <p>会写库并要求 Qdrant/Ollama 可用，默认不参与 mvn test。手动运行：
 * <pre>
 * BACKEND_B_IT=true mvn test -Dtest=BackendBFeatureTest \
 *   -Dspring.datasource.url=jdbc:mysql://localhost:3307/tool_share_verify...
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BACKEND_B_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class BackendBFeatureTest {

    private static final String PASSWORD = "Aa123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private RepairOrderMapper repairOrderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private VectorStore vectorStore;

    @MockitoBean
    private RentalService rentalService;

    @MockitoBean
    private ToolService toolService;

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private ChatModel chatModel;

    @AfterEach
    void restoreResidentStatus() {
        User resident = userMapper.selectById(3L);
        if (resident != null && !Objects.equals(resident.getStatus(), 1)) {
            resident.setStatus(1);
            userMapper.updateById(resident);
        }
    }

    @Test
    void repairFullFlow() throws Exception {
        String resident = login("zhangsan");
        String staff = login("lisi");

        RentalOrderVO ownRental = new RentalOrderVO();
        ownRental.setId(4L);
        ownRental.setUserId(3L);
        ownRental.setToolId(3L);
        ownRental.setOrderNo("SQ-ZY-20261001-0001");
        ownRental.setToolCode("SQ-G-WX-0003");
        ownRental.setToolName("电动扳手");
        when(rentalService.detail(4L)).thenReturn(ownRental);

        RentalOrderVO foreignRental = new RentalOrderVO();
        foreignRental.setId(5L);
        foreignRental.setUserId(999L);
        foreignRental.setToolId(7L);
        when(rentalService.detail(5L)).thenReturn(foreignRental);

        // 提交报修
        JsonNode created = perform(post("/api/repair")
                .header("Authorization", resident)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rentalOrderId":4,"toolId":3,"damagePart":"电机","damageDesc":"插电后不转，接口发黑"}
                        """));
        assertEquals("A200", created.path("code").asText());
        long repairId = created.path("data").path("id").asLong();
        String repairNo = created.path("data").path("repairNo").asText();
        assertTrue(repairNo.matches("SQ-BX-\\d{12}"), "单号格式: " + repairNo);

        // 别人的租赁单不能报修
        expectCode(post("/api/repair")
                .header("Authorization", resident)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rentalOrderId":5,"toolId":7,"damagePart":"车轮","damageDesc":"测试"}
                        """), "C403");

        // 工作人员不能扮演居民报修
        expectCode(post("/api/repair")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rentalOrderId":4,"toolId":3,"damagePart":"电机","damageDesc":"测试"}
                        """), "C403");

        // 居民只看本人，且 toolCode 模糊命中
        JsonNode residentPage = perform(get("/api/repair/page")
                .header("Authorization", resident)
                .param("page", "1").param("size", "10")
                .param("toolCode", "WX-0003"));
        assertEquals("A200", residentPage.path("code").asText());
        assertTrue(containsRecord(residentPage, "id", repairId));
        assertTrue(allRecordsMatch(residentPage, "reporterId", 3L));

        // 详情聚合
        JsonNode detail = perform(get("/api/repair/" + repairId)
                .header("Authorization", resident)).path("data");
        assertEquals("SQ-ZY-20261001-0001", detail.path("orderNo").asText());
        assertEquals("SQ-G-WX-0003", detail.path("toolCode").asText());
        assertEquals("电动扳手", detail.path("toolName").asText());
        assertEquals("张三", detail.path("reporterName").asText());
        assertEquals("138****0003", detail.path("reporterPhone").asText());
        assertTrue(detail.path("progressList").isArray());
        assertEquals(0, detail.path("progressList").size());

        // 居民无权核验；缺字段被 @Valid 拦截
        expectCode(post("/api/repair/" + repairId + "/verify")
                .header("Authorization", resident)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifyResult\":1}"), "C403");
        expectCode(post("/api/repair/" + repairId + "/verify")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"), "C400");

        // 核验确认损坏 → 工具转维修中
        expectCode(post("/api/repair/" + repairId + "/verify")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifyResult\":1,\"remark\":\"电机损坏\"}"), "A200");
        verify(toolService).updateStatus(eq(3L),
                argThat(dto -> Objects.equals(dto.getStatus(), 3)));

        // 进度：维修中 → 回写状态与开始时间
        expectCode(post("/api/repair/" + repairId + "/progress")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":2,\"description\":\"配件到货，开始维修\"}"), "A200");
        RepairOrder repairing = repairOrderMapper.selectById(repairId);
        assertEquals(2, repairing.getRepairStatus());
        assertNotNull(repairing.getRepairStartTime());

        // 进度：维修完成 → 给报修人发系统内消息
        expectCode(post("/api/repair/" + repairId + "/progress")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":3,\"description\":\"维修完成，待验收\"}"), "A200");
        assertEquals(1L, countMessages(repairNo, 3L, 4));

        // 验收合格 → 工具回可用 + 再发消息
        expectCode(post("/api/repair/" + repairId + "/accept")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptResult\":1,\"toolStatus\":1,\"remark\":\"验收合格\"}"), "A200");
        verify(toolService).updateStatus(eq(3L),
                argThat(dto -> Objects.equals(dto.getStatus(), 1)));
        assertEquals(2L, countMessages(repairNo, 3L, 4));
        RepairOrder accepted = repairOrderMapper.selectById(repairId);
        assertEquals(1, accepted.getAcceptResult());
        assertEquals("验收合格", accepted.getRemark());
    }

    @Test
    void userManagement() throws Exception {
        String staff = login("lisi");
        String admin = login("admin");

        JsonNode page = perform(get("/api/user/page")
                .header("Authorization", staff)
                .param("page", "1").param("size", "10")
                .param("realName", "张"));
        assertEquals("A200", page.path("code").asText());
        assertTrue(containsRecord(page, "id", 3L));
        assertTrue(allRecordsMatchText(page, "phone", "138****0003"));

        // 管理员不能给自己降级
        expectCode(put("/api/user/1/role")
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":0}"), "C400");

        // 工作人员无权改角色
        expectCode(put("/api/user/3/role")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":1}"), "C403");

        // 停用后拒绝登录，再启用恢复
        expectCode(put("/api/user/3/status")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"), "A200");
        expectCode(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"zhangsan","password":"Aa123456"}
                        """), "C403");

        expectCode(put("/api/user/3/status")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":1}"), "A200");
        login("zhangsan");
    }

    @Test
    void notifyFeatures() throws Exception {
        String resident = login("zhangsan");
        String staff = login("lisi");

        JsonNode page = perform(get("/api/notify/page")
                .header("Authorization", resident)
                .param("page", "1").param("size", "10"));
        assertEquals("A200", page.path("code").asText());
        assertTrue(allRecordsMatch(page, "userId", 3L));

        // 不能读别人的消息
        expectCode(put("/api/notify/1/read")
                .header("Authorization", staff), "C404");

        // 标记单条已读
        expectCode(put("/api/notify/4/read")
                .header("Authorization", resident), "A200");
        assertEquals(1, messageMapper.selectById(4L).getReadStatus());

        // 全部已读
        expectCode(put("/api/notify/read-all")
                .header("Authorization", resident), "A200");
        Long unread = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, 3L)
                .eq(Message::getReadStatus, 0));
        assertEquals(0L, unread);

        // 配置：默认系统内，可切短信并读回
        expectCode(get("/api/notify/config")
                .header("Authorization", staff), "A200");
        JsonNode config = perform(get("/api/notify/config")
                .header("Authorization", staff));
        assertEquals(1, config.path("data").path("channel").asInt());
        expectCode(put("/api/notify/config")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":2}"), "A200");
        JsonNode sms = perform(get("/api/notify/config")
                .header("Authorization", staff));
        assertEquals(2, sms.path("data").path("channel").asInt());
        expectCode(put("/api/notify/config")
                .header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":1}"), "A200");

        // 居民无权看/改配置
        expectCode(get("/api/notify/config")
                .header("Authorization", resident), "C403");
    }

    @Test
    void aiRagFlow() throws Exception {
        String admin = login("admin");
        String staff = login("lisi");
        String resident = login("zhangsan");

        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("mock answer")))));

        // 上传 → Tika 解析 → 切分 → 向量入库 → 回填片段数/状态
        byte[] pdf;
        try (var in = getClass().getResourceAsStream("/kb-test.pdf")) {
            pdf = Objects.requireNonNull(in, "缺少 kb-test.pdf 测试资源").readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile("file", "kb-test.pdf", "application/pdf", pdf);
        JsonNode uploadResponse = perform(multipart("/api/ai/documents")
                .file(file)
                .header("Authorization", admin));
        assertEquals("A200", uploadResponse.path("code").asText());
        JsonNode uploaded = uploadResponse.path("data");
        long docId = uploaded.path("id").asLong();
        assertEquals(2, uploaded.path("status").asInt());
        assertTrue(uploaded.path("chunkCount").asInt() >= 1);

        JsonNode docs = perform(get("/api/ai/documents")
                .header("Authorization", staff)
                .param("page", "1").param("size", "10"));
        assertEquals("A200", docs.path("code").asText());
        assertTrue(containsRecord(docs, "id", docId));

        // 问答：检索命中上传文档，来源带 metadata.fileName
        JsonNode chat = perform(post("/api/ai/chat")
                .header("Authorization", resident)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"保修期多久？\"}")).path("data");
        assertEquals("mock answer", chat.path("answer").asText());
        assertTrue(hasSourceDocument(chat, "kb-test.pdf"), "sources 应包含上传文档");

        // 删除 → MinIO 对象 + Qdrant 向量 + 记录
        expectCode(delete("/api/ai/documents/" + docId)
                .header("Authorization", admin), "A200");

        List<Document> remaining = vectorStore.similaritySearch(SearchRequest.builder()
                .query("warranty period")
                .topK(10)
                .similarityThresholdAll()
                .filterExpression(new FilterExpressionBuilder().eq("documentId", String.valueOf(docId)).build())
                .build());
        assertTrue(remaining.isEmpty(), "删除后不应再检索到该文档片段");

        JsonNode after = perform(get("/api/ai/documents")
                .header("Authorization", staff)
                .param("page", "1").param("size", "10"));
        assertFalse(containsRecord(after, "id", docId));
    }

    private String login(String username) throws Exception {
        JsonNode response = perform(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)));
        assertEquals("A200", response.path("code").asText());
        String token = response.path("data").path("token").asText();
        assertFalse(token.isBlank(), "登录应返回 token");
        return token;
    }

    private JsonNode perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void expectCode(RequestBuilder request, String code) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    private boolean containsRecord(JsonNode response, String field, long id) {
        for (JsonNode record : response.path("data").path("records")) {
            if (record.path(field).asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private boolean allRecordsMatch(JsonNode response, String field, long value) {
        for (JsonNode record : response.path("data").path("records")) {
            if (record.path(field).asLong() != value) {
                return false;
            }
        }
        return true;
    }

    private boolean allRecordsMatchText(JsonNode response, String field, String value) {
        for (JsonNode record : response.path("data").path("records")) {
            if (!value.equals(record.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSourceDocument(JsonNode chatData, String documentName) {
        for (JsonNode source : chatData.path("sources")) {
            if (documentName.equals(source.path("documentName").asText())) {
                return true;
            }
        }
        return false;
    }

    private long countMessages(String bizNo, long userId, int type) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizNo, bizNo)
                .eq(Message::getUserId, userId)
                .eq(Message::getType, type));
    }
}
