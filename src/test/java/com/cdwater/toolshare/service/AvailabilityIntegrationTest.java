package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #13 可预约时段：首尾相接、整段占用、跨天、待支付过期释放、取消/归还释放、建议窗口时长、工具不可租。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AvailabilityIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String TOOL_PREFIX = "IT-AVAIL-";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolLogMapper toolLogMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();
    final List<Long> rentalIds = new ArrayList<>();
    final List<Long> toolIds = new ArrayList<>();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : rentalIds) {
            rentalMapper.deleteById(id);
        }
        for (Long id : toolIds) {
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, id));
            toolMapper.deleteById(id);
        }
        rentalIds.clear();
        toolIds.clear();
    }

    @Test
    void adjacent_rental_ending_at_window_start_does_not_block() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "adjacent");
        LocalDateTime start = tomorrowAt(10);
        insertRental(toolId, "RESERVED", start.minusHours(2), start);

        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        assertTrue(data.get("available").asBoolean(), data.toString());
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        assertTime(start, data.get("freeWindows").get(0), "startTime");
        assertTime(start.plusHours(2), data.get("freeWindows").get(0), "endTime");
        assertEquals(0, data.get("suggestionWindows").size(), data.toString());
    }

    @Test
    void merged_rentals_split_free_windows() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "merged");
        LocalDateTime start = tomorrowAt(10);
        insertRental(toolId, "RESERVED", start, start.plusHours(1));
        insertRental(toolId, "PICKED_UP", start.plusHours(1), start.plusHours(2));

        JsonNode data = availability(user, toolId, start, start.plusHours(3));

        assertFalse(data.get("available").asBoolean(), data.toString());
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        assertTime(start.plusHours(2), data.get("freeWindows").get(0), "startTime");
        assertTime(start.plusHours(3), data.get("freeWindows").get(0), "endTime");
    }

    @Test
    void fully_occupied_window_suggests_slots_of_requested_duration() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "full");
        LocalDateTime start = tomorrowAt(10);
        insertRental(toolId, "RESERVED", start.minusHours(1), start.plusHours(5));

        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        assertFalse(data.get("available").asBoolean(), data.toString());
        assertEquals(0, data.get("freeWindows").size(), data.toString());
        JsonNode suggestions = data.get("suggestionWindows");
        assertFalse(suggestions.isEmpty(), data.toString());
        Duration requested = Duration.ofHours(2);
        for (JsonNode slot : suggestions) {
            LocalDateTime slotStart = time(slot, "startTime");
            LocalDateTime slotEnd = time(slot, "endTime");
            assertEquals(requested, Duration.between(slotStart, slotEnd), "建议窗口必须满足所需时长");
            assertFalse(slotStart.isBefore(start), "建议窗口不得早于期望开始时间");
        }
        // 最近建议从占用结束（15:00）开始，而不是占用结束 +1 小时
        assertTime(start.plusHours(5), suggestions.get(0), "startTime");
    }

    @Test
    void cross_day_occupancy_leaves_free_window_across_midnight() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "cross-day");
        LocalDateTime start = tomorrowAt(20);
        // 占用 22:00 - 次日 01:00
        insertRental(toolId, "OVERDUE", start.plusHours(2), start.plusHours(5));

        // 期望 23:00 - 次日 03:00
        JsonNode data = availability(user, toolId, start.plusHours(3), start.plusHours(7));

        assertFalse(data.get("available").asBoolean(), data.toString());
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        assertTime(start.plusHours(5), data.get("freeWindows").get(0), "startTime");
        assertTime(start.plusHours(7), data.get("freeWindows").get(0), "endTime");
    }

    @Test
    void non_expired_pending_payment_blocks_window() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "pending-active");
        LocalDateTime start = tomorrowAt(10);
        insertRental(toolId, "PENDING_PAYMENT", start, start.plusHours(2));

        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        assertFalse(data.get("available").asBoolean(), data.toString());
    }

    @Test
    void expired_pending_payment_releases_window() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "pending-expired");
        LocalDateTime start = tomorrowAt(10);
        long rentalId = insertRental(toolId, "PENDING_PAYMENT", start, start.plusHours(2));
        // 与数据库时钟比较，避免应用与 MySQL 容器时区不一致
        jdbcTemplate.update(
                "UPDATE t_rental SET create_time = TIMESTAMPADD(MINUTE, -31, NOW()) WHERE id = ?", rentalId);

        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        assertTrue(data.get("available").asBoolean(), data.toString());
    }

    @Test
    void cancelled_and_returned_rentals_release_window() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "released");
        LocalDateTime start = tomorrowAt(10);
        insertRental(toolId, "CANCELLED", start, start.plusHours(2));
        insertRental(toolId, "RETURNED", start, start.plusHours(2));

        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        assertTrue(data.get("available").asBoolean(), data.toString());
    }

    @Test
    void unrentable_tool_returns_reason() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "unavailable");
        LocalDateTime start = tomorrowAt(10);

        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status", admin,
                Map.of("status", "MAINTENANCE")).status());
        JsonNode maintenance = availability(user, toolId, start, start.plusHours(2));
        assertFalse(maintenance.get("available").asBoolean(), maintenance.toString());
        assertTrue(maintenance.get("message").asString().contains("不可预约"), maintenance.toString());

        long pendingAuditId = createTool(admin, "pending-audit");
        JsonNode pendingAudit = availability(user, pendingAuditId, start, start.plusHours(2));
        assertFalse(pendingAudit.get("available").asBoolean(), pendingAudit.toString());
        assertTrue(pendingAudit.get("message").asString().contains("审核"), pendingAudit.toString());
    }

    private JsonNode availability(String token, long toolId, LocalDateTime start, LocalDateTime end) {
        String url = "/api/user/tools/" + toolId + "/availability"
                + "?startTime=" + start.format(FORMAT) + "&endTime=" + end.format(FORMAT);
        return ok(call(HttpMethod.GET, url, token, null));
    }

    private long insertRental(long toolId, String status, LocalDateTime start, LocalDateTime end) {
        Rental rental = new Rental();
        rental.setOrderNo("IT-AVAIL-" + SEQ.incrementAndGet());
        rental.setToolId(toolId);
        rental.setToolCode("IT-AVAIL-CODE");
        rental.setToolName("可用性测试工具");
        rental.setUserId(1L);
        rental.setRenterName("王建国");
        rental.setRenterPhone("13800000001");
        rental.setRenterIdCardMasked("3101********1234");
        rental.setStartTime(start);
        rental.setEndTime(end);
        rental.setPickupPerson("王建国");
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("PENDING_PAYMENT".equals(status) ? "UNPAID" : "PAID");
        rental.setStatus(status);
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    private long createApprovedTool(String admin, String suffix) {
        long id = createTool(admin, suffix);
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    private long createTool(String admin, String suffix) {
        String name = TOOL_PREFIX + suffix;
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", name,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", new BigDecimal("100"),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("id").asLong();
        toolIds.add(id);
        return id;
    }

    private LocalDateTime tomorrowAt(int hour) {
        return LocalDateTime.now().plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime time(JsonNode node, String field) {
        return LocalDateTime.parse(node.get(field).asString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private void assertTime(LocalDateTime expected, JsonNode node, String field) {
        assertEquals(expected, time(node, field), node.toString());
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body().get("data");
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
