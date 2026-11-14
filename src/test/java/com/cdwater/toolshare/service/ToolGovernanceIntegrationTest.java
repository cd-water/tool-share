package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：工具审核门禁、状态机守卫与富文本净化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolGovernanceIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-GOV-";

    @LocalServerPort
    int port;

    @Autowired
    ToolMapper toolMapper;

    @Autowired
    ToolLogMapper toolLogMapper;

    @Autowired
    RentalMapper rentalMapper;

    @Autowired
    DamageReportMapper damageReportMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

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
    void audit_gates_user_visibility_and_booking() {
        String admin = adminLogin();
        String user = userLogin();
        JsonNode created = createTool(admin, "audit", "<p>正常说明</p>");
        long toolId = created.get("id").asLong();

        assertEquals(0, created.get("auditStatus").asInt());
        assertEquals("待审核", created.get("auditStatusName").asString());
        assertEquals("admin", created.get("createdByName").asString());

        assertEquals(0, get("/api/user/tools?keyword=" + PREFIX + "audit", user)
                .get("data").get("total").asInt());
        assertCode("C404", get("/api/user/tools/" + toolId, user));

        ApiResponse booking = call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId));
        assertEquals(400, booking.status(), booking.body().toString());
        assertTrue(booking.body().get("message").asString().contains("审核"),
                booking.body().toString());

        ApiResponse needsRemark = call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "NEEDS_CHANGE"));
        assertEquals(400, needsRemark.status(), needsRemark.body().toString());
        assertTrue(needsRemark.body().get("message").asString().contains("审核意见"));

        JsonNode needsChange = ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "NEEDS_CHANGE", "remark", "请补充存放区域"))).get("data");
        assertEquals(2, needsChange.get("auditStatus").asInt());
        assertEquals("需修改", needsChange.get("auditStatusName").asString());
        assertEquals("请补充存放区域", needsChange.get("auditRemark").asString());
        assertCode("C404", get("/api/user/tools/" + toolId, user));

        JsonNode approved = ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "APPROVED", "remark", "通过"))).get("data");
        assertEquals(1, approved.get("auditStatus").asInt());
        assertEquals("已通过", approved.get("auditStatusName").asString());

        assertEquals(1, get("/api/user/tools?keyword=" + PREFIX + "audit", user)
                .get("data").get("total").asInt());
        assertEquals("A200", get("/api/user/tools/" + toolId, user).get("code").asString());
        JsonNode rental = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId))).get("data");
        assertEquals("PENDING_PAYMENT", rental.get("status").asString());

        JsonNode logs = get("/api/admin/tools/" + toolId + "/logs", admin).get("data");
        assertTrue(hasLog(logs, "CREATE", null, "AVAILABLE"), logs.toString());
        assertTrue(hasLog(logs, "AUDIT", "NEEDS_CHANGE", "APPROVED"), logs.toString());
        JsonNode auditLog = findLog(logs, "AUDIT", "NEEDS_CHANGE", "APPROVED");
        assertEquals("admin", auditLog.get("operatorName").asString());
        assertEquals("通过", auditLog.get("remark").asString());
    }

    @Test
    void cannot_be_available_with_active_rental_or_unfinished_report() {
        String admin = adminLogin();
        String user = userLogin();

        long activeTool = createApprovedTool(admin, "active");
        ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(activeTool)));
        ApiResponse guardedByRental = call(HttpMethod.PATCH,
                "/api/admin/tools/" + activeTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, guardedByRental.status(), guardedByRental.body().toString());
        assertTrue(guardedByRental.body().get("message").asString().contains("活动租赁"));

        long repairTool = createApprovedTool(admin, "repair");
        JsonNode rental = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(repairTool))).get("data");
        long rentalId = rental.get("id").asLong();
        ok(call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()));
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup", admin, Map.of()));
        ok(call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                "rentalId", rentalId, "damagePart", "电机", "description", "异响", "images", List.of())));
        // 带伤归还：租赁结束但报修尚未完成，工具转维修中
        Rental toReturn = rentalMapper.selectById(rentalId);
        toReturn.setStartTime(LocalDateTime.now().minusDays(1).withNano(0));
        rentalMapper.updateById(toReturn);
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return", admin,
                Map.of("actualReturnTime", LocalDateTime.now().withNano(0).format(FORMAT),
                        "damageResult", "SEVERE", "remark", "带伤归还")));

        ApiResponse guardedByReport = call(HttpMethod.PATCH,
                "/api/admin/tools/" + repairTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, guardedByReport.status(), guardedByReport.body().toString());
        assertTrue(guardedByReport.body().get("message").asString().contains("未完成报修"));

        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + repairTool + "/status",
                admin, Map.of("status", "SCRAPPED")));
        ApiResponse scrapped = call(HttpMethod.PATCH,
                "/api/admin/tools/" + repairTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, scrapped.status(), scrapped.body().toString());
        assertTrue(scrapped.body().get("message").asString().contains("已报废"));
    }

    @Test
    void usage_guide_is_sanitized_but_safe_content_remains() {
        String admin = adminLogin();
        String dirty = """
                <p onclick="alert(1)">正常 <strong>图文</strong> 说明</p>
                <script>alert('xss')</script>
                <a href="javascript:alert(1)">坏链接</a>
                <img src="/api/files/guide.png" onerror="alert(1)" alt="示意图">
                """;
        long toolId = createTool(admin, "sanitize", dirty).get("id").asLong();

        JsonNode rows = get("/api/admin/tools?keyword=" + PREFIX + "sanitize", admin)
                .get("data").get("records");
        assertEquals(1, rows.size(), rows.toString());
        String guide = rows.get(0).get("usageGuide").asString();

        assertFalse(guide.toLowerCase().contains("<script"), guide);
        assertFalse(guide.toLowerCase().contains("onclick"), guide);
        assertFalse(guide.toLowerCase().contains("javascript:"), guide);
        assertFalse(guide.toLowerCase().contains("onerror"), guide);
        assertTrue(guide.contains("<strong>图文</strong>"), guide);
        assertTrue(guide.contains("src=\"/api/files/guide.png\""), guide);
        assertEquals("待审核", rows.get(0).get("auditStatusName").asString());

        JsonNode logs = get("/api/admin/tools/" + toolId + "/logs", admin).get("data");
        assertTrue(hasLog(logs, "CREATE", null, "AVAILABLE"), logs.toString());
    }

    private long createApprovedTool(String admin, String suffix) {
        long id = createTool(admin, suffix, "<p>说明</p>").get("id").asLong();
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")));
        return id;
    }

    private JsonNode createTool(String admin, String suffix, String usageGuide) {
        return ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", usageGuide,
                "imageUrls", List.of()))).get("data");
    }

    private Map<String, Object> booking(long toolId) {
        return Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国");
    }

    private boolean hasLog(JsonNode logs, String action, String from, String to) {
        return findLog(logs, action, from, to) != null;
    }

    private JsonNode findLog(JsonNode logs, String action, String from, String to) {
        for (JsonNode log : logs) {
            boolean sameAction = action.equals(log.get("action").asString());
            JsonNode fromNode = log.get("fromStatus");
            JsonNode toNode = log.get("toStatus");
            boolean sameFrom = from == null
                    ? fromNode == null || fromNode.isNull()
                    : fromNode != null && from.equals(fromNode.asString());
            boolean sameTo = to == null
                    ? toNode == null || toNode.isNull()
                    : toNode != null && to.equals(toNode.asString());
            if (sameAction && sameFrom && sameTo) {
                return log;
            }
        }
        return null;
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    private void assertCode(String code, JsonNode body) {
        assertEquals(code, body.get("code").asString(), body.toString());
    }

    private JsonNode get(String url, String token) {
        return call(HttpMethod.GET, url, token, null).body();
    }

    private String adminLogin() {
        return login("admin", "Aa123456", "/api/admin/login");
    }

    private String userLogin() {
        return login("13800000001", "Aa123456", "/api/user/login/by-pwd");
    }

    private String login(String username, String password, String url) {
        String accountField = url.contains("/admin/") ? "username" : "phone";
        ApiResponse resp = call(HttpMethod.POST, url, null,
                Map.of(accountField, username, "password", password));
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }

    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            try (InputStream in = response.getBody()) {
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
