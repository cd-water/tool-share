package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.rental.RentalService;
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
 * 租赁单并发与状态机集成测试：并发预约、支付/取消竞争、非法跃迁、取件前置条件、逾期物化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-RENTAL-";

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
    @Autowired
    RentalService rentalService;

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
    void concurrent_booking_for_same_slot_only_one_succeeds() throws Exception {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "concurrent");
        Map<String, Object> booking = booking(toolId);

        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/rentals", user, booking),
                () -> call(HttpMethod.POST, "/api/user/rentals", user, booking));

        long success = results.stream().filter(r -> "A200".equals(code(r))).count();
        long conflict = results.stream().filter(r -> "C409".equals(code(r))).count();
        assertEquals(1, success, results.toString());
        assertEquals(1, conflict, results.toString());
        assertEquals(1, rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)));
    }

    @Test
    void unexpired_pending_payment_blocks_booking() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "pending-block");
        Map<String, Object> booking = booking(toolId);

        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals", user, booking).status());
        ApiResponse second = call(HttpMethod.POST, "/api/user/rentals", user, booking);
        assertEquals("C409", code(second), second.body().toString());
    }

    @Test
    void concurrent_pay_and_cancel_keep_deposit_consistent() throws Exception {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "pay-cancel");
        long rentalId = createRental(user, toolId);

        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()),
                () -> call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/cancel", user,
                        Map.of("reason", "并发取消")));

        ApiResponse pay = results.get(0);
        ApiResponse cancel = results.get(1);
        assertTrue(200 == cancel.status(), cancel.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals("CANCELLED", rental.getStatus());
        if (200 == pay.status()) {
            assertEquals("REFUNDED", rental.getDepositStatus());
            assertEquals(0, rental.getRefundAmount().compareTo(rental.getDepositAmount()));
        } else {
            assertEquals("C409", code(pay), pay.body().toString());
            assertEquals("UNPAID", rental.getDepositStatus());
            assertEquals(0, rental.getRefundAmount().signum());
        }
    }

    @Test
    void illegal_transitions_return_409() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "state");
        long rentalId = createRental(user, toolId);
        String base = "/api/admin/rentals/" + rentalId;

        assertCode("C409", call(HttpMethod.POST, base + "/pickup", admin, Map.of()));
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertCode("C409", call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()));
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/cancel",
                user, Map.of("reason", "行程变化")).status());
        assertCode("C409", call(HttpMethod.POST, base + "/pickup", admin, Map.of()));
        assertCode("C409", call(HttpMethod.POST, base + "/return", admin,
                Map.of("damageResult", "INTACT")));
    }

    @Test
    void pickup_requires_paid_deposit_and_available_tool() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "pickup-guard");
        long rentalId = createRental(user, toolId);
        String pickupUrl = "/api/admin/rentals/" + rentalId + "/pickup";

        ApiResponse unpaid = call(HttpMethod.POST, pickupUrl, admin, Map.of());
        assertCode("C409", unpaid);
        assertTrue(unpaid.body().get("message").asString().contains("押金"), unpaid.body().toString());

        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "MAINTENANCE")).status());

        ApiResponse unavailable = call(HttpMethod.POST, pickupUrl, admin, Map.of());
        assertCode("C409", unavailable);
        assertTrue(unavailable.body().get("message").asString().contains("不可"),
                unavailable.body().toString());
    }

    @Test
    void mark_overdue_is_conditional_and_notifies_once() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "overdue");
        long rentalId = createRental(user, toolId);
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());

        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                .eq(Rental::getId, rentalId)
                .set(Rental::getEndTime, LocalDateTime.now().minusHours(1)));

        rentalService.markOverdue();
        rentalService.markOverdue();

        assertEquals("OVERDUE", rentalMapper.selectById(rentalId).getStatus());
        assertEquals(1, messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, "OVERDUE:" + rentalId)));
    }

    private long createRental(String userToken, long toolId) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, booking(toolId)));
        return created.get("data").get("id").asLong();
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
