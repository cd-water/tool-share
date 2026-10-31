package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.entity.DamageReport;
import com.cdwater.toolshare.entity.Rental;
import com.cdwater.toolshare.entity.Tool;
import com.cdwater.toolshare.entity.ToolLog;
import com.cdwater.toolshare.mapper.DamageReportMapper;
import com.cdwater.toolshare.mapper.RentalMapper;
import com.cdwater.toolshare.mapper.ToolLogMapper;
import com.cdwater.toolshare.mapper.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：工具创建 → 预约 → 支付 → 取件 → 报修 → 维修验收 → 归还。
 * 前置：docker compose up -d；seed 用户 13800000001/Aa123456、admin/Aa123456。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessFlowIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-集成测试-";

    @LocalServerPort
    int port;

    @Autowired
    ToolMapper toolMapper;

    @Autowired
    RentalMapper rentalMapper;

    @Autowired
    DamageReportMapper damageReportMapper;

    @Autowired
    ToolLogMapper toolLogMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void cleanUp() {
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        for (Tool tool : tools) {
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            toolMapper.deleteById(tool.getId());
        }
    }

    @Test
    void rental_and_damage_lifecycle() {
        String adminToken = adminLogin();
        String userToken = userLogin();
        long toolId = createTool(adminToken, "生命周期", "120");
        String start = LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT);

        JsonNode created = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        assertEquals("A200", created.get("code").asString(), created.toString());
        long rentalId = created.get("data").get("id").asLong();
        assertEquals("PENDING_PAYMENT", created.get("data").get("status").asString());
        assertTrue(created.get("data").get("orderNo").asString().startsWith("SQ-ZY-"));

        assertEquals("A200", post("/api/user/rentals/" + rentalId + "/pay", userToken, Map.of())
                .get("code").asString());
        assertEquals("A200", post("/api/admin/rentals/" + rentalId + "/pickup", adminToken, Map.of())
                .get("code").asString());
        assertEquals("PICKED_UP", get("/api/user/rentals/" + rentalId, userToken)
                .get("data").get("status").asString());

        JsonNode report = post("/api/user/damage-reports", userToken, Map.of(
                "rentalId", rentalId,
                "damagePart", "电源接口",
                "description", "通电后无反应",
                "images", List.of()));
        assertEquals("A200", report.get("code").asString(), report.toString());
        long reportId = report.get("data").get("id").asLong();
        assertTrue(report.get("data").get("reportNo").asString().startsWith("SQ-BX-"));

        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/verify", adminToken,
                Map.of("verifyResult", "CONFIRMED", "repairPerson", "王师傅",
                        "repairEstimate", "2 天", "remark", "确认线路故障")).get("code").asString());
        assertEquals("MAINTENANCE", get("/api/user/tools/" + toolId, userToken)
                .get("data").get("status").asString());

        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/progress", adminToken,
                Map.of("progressStatus", "REPAIRING", "progressNote", "已更换电源线",
                        "repairCost", 30)).get("code").asString());
        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/accept", adminToken,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE",
                        "remark", "恢复正常")).get("code").asString());
        assertEquals("COMPLETED", get("/api/admin/damage-reports/" + reportId, adminToken)
                .get("data").get("status").asString());
        assertEquals("AVAILABLE", get("/api/user/tools/" + toolId, userToken)
                .get("data").get("status").asString());

        assertEquals("A200", post("/api/admin/rentals/" + rentalId + "/return", adminToken,
                Map.of("damageResult", "INTACT", "remark", "验收完好")).get("code").asString());
        JsonNode returned = get("/api/user/rentals/" + rentalId, userToken).get("data");
        assertEquals("RETURNED", returned.get("status").asString());
        assertEquals("REFUNDED", returned.get("depositStatus").asString());
        assertEquals(120, returned.get("refundAmount").asInt());
        assertTrue(get("/api/user/messages?page=1&size=20", userToken)
                .get("data").get("total").asInt() > 0);
    }

    @Test
    void upload_and_read_file() {
        String userToken = userLogin();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource("集成测试文件内容".getBytes(StandardCharsets.UTF_8)))
                .filename("测试-集成.txt")
                .contentType(MediaType.TEXT_PLAIN);

        String raw = client.post().uri("/api/user/upload")
                .header("Authorization", "Bearer " + userToken)
                .body(body.build())
                .retrieve().body(String.class);
        JsonNode node = om.readTree(raw == null ? "{}" : raw);
        assertEquals("A200", node.get("code").asString(), node.toString());
        String url = node.get("data").get("url").asString();
        assertTrue(url.startsWith("/api/files/"));

        byte[] content = client.get().uri(url).retrieve().body(byte[].class);
        assertEquals("集成测试文件内容", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void knowledge_upload_and_delete() {
        String adminToken = adminLogin();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(
                "集成测试知识库内容：租赁工具需按时归还，损坏需报修。".getBytes(StandardCharsets.UTF_8)))
                .filename("测试-知识库.txt")
                .contentType(MediaType.TEXT_PLAIN);
        body.part("title", PREFIX + "知识库");

        String raw = client.post().uri("/api/admin/knowledge")
                .header("Authorization", "Bearer " + adminToken)
                .body(body.build())
                .retrieve().body(String.class);
        JsonNode node = om.readTree(raw == null ? "{}" : raw);
        assertEquals("A200", node.get("code").asString(), node.toString());
        long id = node.get("data").get("id").asLong();

        assertTrue(get("/api/admin/knowledge", adminToken).get("data").toString().contains(PREFIX + "知识库"));
        assertEquals("A200", delete("/api/admin/knowledge/" + id, adminToken).get("code").asString());
        assertFalse(get("/api/admin/knowledge", adminToken).get("data").toString().contains(PREFIX + "知识库"));
    }

    @Test
    void conflict_and_cancel() {
        String adminToken = adminLogin();
        String userToken = userLogin();
        long toolId = createTool(adminToken, "冲突", "80");
        String start = LocalDateTime.now().plusDays(4).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(5).withNano(0).format(FORMAT);

        JsonNode first = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        assertEquals("A200", first.get("code").asString(), first.toString());
        long rentalId = first.get("data").get("id").asLong();
        post("/api/user/rentals/" + rentalId + "/pay", userToken, Map.of());

        JsonNode conflict = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        assertEquals("C400", conflict.get("code").asString());

        assertEquals("A200", post("/api/user/rentals/" + rentalId + "/cancel", userToken,
                Map.of("reason", "临时有事")).get("code").asString());
        JsonNode cancelled = get("/api/user/rentals/" + rentalId, userToken).get("data");
        assertEquals("CANCELLED", cancelled.get("status").asString());
        assertEquals("REFUNDED", cancelled.get("depositStatus").asString());
        assertFalse(cancelled.get("refundAmount").isNull());
    }

    private long createTool(String token, String suffix, String deposit) {
        JsonNode node = post("/api/admin/tools", token, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", Integer.parseInt(deposit),
                "storageArea", "测试柜",
                "usageGuide", "测试说明",
                "imageUrls", List.of()));
        assertEquals("A200", node.get("code").asString(), node.toString());
        assertTrue(node.get("data").get("code").asString().matches("SQ-G-WX-\\d{4}"));
        return node.get("data").get("id").asLong();
    }

    private String userLogin() {
        JsonNode node = post("/api/user/login/by-pwd", null,
                Map.of("phone", "13800000001", "password", "Aa123456"));
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    private String adminLogin() {
        JsonNode node = post("/api/admin/login", null,
                Map.of("username", "admin", "password", "Aa123456"));
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    private JsonNode post(String url, String token, Object body) {
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        String raw = spec.body(body).retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode get(String url, String token) {
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        String raw = spec.retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode delete(String url, String token) {
        RestClient.RequestHeadersSpec<?> spec = client.delete().uri(url);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        String raw = spec.retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }
}
