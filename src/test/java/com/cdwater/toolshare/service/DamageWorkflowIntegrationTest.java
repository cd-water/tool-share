package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：报修单状态机、核验、维修进度、验收与唯一活动报修约束。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class DamageWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-DMG-";

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
    @Autowired
    MessageMapper messageMapper;

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
    void illegal_transitions_return_409() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "illegal");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);

        // 未核验不可更新进度 / 验收
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "开始维修")));
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED", "repairPerson", "王师傅")));
        // 不可重复核验
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        // 维修未完成不可验收
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));
        assertEquals("COMPLETED", report(reportId).getStatus());
        // 终态不可再流转
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "MAINTENANCE")));
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "重复维修")));
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "FALSE")));
    }

    @Test
    void false_report_is_rejected_without_touching_tool() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "false");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);

        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "FALSE", "remark", "用户误报")));

        assertEquals("REJECTED", report(reportId).getStatus());
        assertEquals("RENTED", toolMapper.selectById(toolId).getStatus());
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "不应处理")));
    }

    @Test
    void old_report_cannot_touch_a_returned_or_newly_rented_tool() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "stale");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);

        // 模拟租赁已归还且工具已被新租赁占用
        Rental returned = rentalMapper.selectById(rentalId);
        returned.setStatus("RETURNED");
        rentalMapper.updateById(returned);
        Tool tool = toolMapper.selectById(toolId);
        tool.setStatus("RENTED");
        toolMapper.updateById(tool);

        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        assertEquals("RENTED", toolMapper.selectById(toolId).getStatus());
        assertEquals("SUBMITTED", report(reportId).getStatus());
    }

    @Test
    void concurrent_create_only_one_active_report_succeeds() throws Exception {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "concurrent");
        long rentalId = createPickedUpRental(user, admin, toolId);
        Map<String, Object> body = Map.of(
                "rentalId", rentalId,
                "damagePart", "电机",
                "description", "异响",
                "images", List.of());

        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/damage-reports", user, body),
                () -> call(HttpMethod.POST, "/api/user/damage-reports", user, body));

        long success = results.stream().filter(r -> "A200".equals(code(r))).count();
        long conflict = results.stream().filter(r -> "C409".equals(code(r))).count();
        assertEquals(1, success, results.toString());
        assertEquals(1, conflict, results.toString());
        assertEquals(1, damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rentalId)
                .in(DamageReport::getStatus, List.of("SUBMITTED", "VERIFIED", "REPAIRING"))));
    }

    @Test
    void repair_completed_notifies_resident_and_staff() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "notify");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "SUSPECTED")));
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "维修中")));
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));

        DamageReport report = report(reportId);
        String reportNo = report.getReportNo();
        assertTrue(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, "USER")
                .eq(Message::getReceiverId, report.getUserId())
                .eq(Message::getTitle, "维修进度更新")
                .like(Message::getContent, "维修完成")) >= 1, "居民应收到维修完成通知");
        assertTrue(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, "ADMIN")
                .eq(Message::getTitle, "维修已完成")
                .like(Message::getContent, reportNo)) >= 1, "工作人员应收到维修完成通知");
    }

    @Test
    void acceptance_combines_result_with_tool_target_status() {
        String admin = adminLogin();
        String user = userLogin();

        // 部分合格 / 不合格不得直接置为可用
        long toolId = createApprovedTool(admin, "combo");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);
        verifyAndComplete(admin, reportId);

        assertCode("C400", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "AVAILABLE")));
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "MAINTENANCE")));
        assertEquals("COMPLETED", report(reportId).getStatus());
        assertEquals("MAINTENANCE", toolMapper.selectById(toolId).getStatus());
    }

    @Test
    void qualified_accept_keeps_tool_maintenance_while_rental_is_active() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "active");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);
        verifyAndComplete(admin, reportId);

        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        assertEquals("COMPLETED", report(reportId).getStatus());
        assertEquals("MAINTENANCE", toolMapper.selectById(toolId).getStatus());
    }

    @Test
    void qualified_accept_makes_tool_available_after_rental_ended() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "ended");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);
        verifyAndComplete(admin, reportId);

        Rental rental = rentalMapper.selectById(rentalId);
        rental.setStatus("RETURNED");
        rentalMapper.updateById(rental);

        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        assertEquals("COMPLETED", report(reportId).getStatus());
        assertEquals("AVAILABLE", toolMapper.selectById(toolId).getStatus());
    }

    @Test
    void scrapped_is_terminal_and_cannot_be_reopened() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "scrapped");
        long rentalId = createPickedUpRental(user, admin, toolId);
        long reportId = createReport(user, rentalId);
        verifyAndComplete(admin, reportId);

        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "SCRAPPED")));

        assertEquals("SCRAPPED", report(reportId).getStatus());
        assertEquals("SCRAPPED", toolMapper.selectById(toolId).getStatus());
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "重新维修")));
    }

    private void verifyAndComplete(String admin, long reportId) {
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));
    }

    private String base(long reportId) {
        return "/api/admin/damage-reports/" + reportId;
    }

    private long createReport(String user, long rentalId) {
        return ok(call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                "rentalId", rentalId,
                "damagePart", "电源接口",
                "description", "通电后无反应",
                "images", List.of()))).get("data").get("id").asLong();
    }

    private DamageReport report(long reportId) {
        return damageReportMapper.selectById(reportId);
    }

    private long createPickedUpRental(String user, String admin, long toolId) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId)));
        long rentalId = created.get("data").get("id").asLong();
        ok(call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()));
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup", admin, Map.of()));
        return rentalId;
    }

    private Map<String, Object> booking(long toolId) {
        return Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国");
    }

    private long createApprovedTool(String admin, String suffix) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("data").get("id").asLong();
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    private List<ApiResponse> concurrently(Supplier<ApiResponse> first,
                                           Supplier<ApiResponse> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ApiResponse> a = pool.submit(() -> await(ready, go, first));
            Future<ApiResponse> b = pool.submit(() -> await(ready, go, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private ApiResponse await(CountDownLatch ready, CountDownLatch go,
                              Supplier<ApiResponse> call) throws InterruptedException {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return call.get();
    }

    private String code(ApiResponse response) {
        return response.body().get("code").asString();
    }

    private void assertCode(String code, ApiResponse response) {
        assertEquals(code, code(response), response.body().toString());
        if ("C409".equals(code)) {
            assertEquals(409, response.status(), response.body().toString());
        }
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", code(response), response.body().toString());
        return response.body();
    }

    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    private String login(String url, Map<String, Object> body) {
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
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
