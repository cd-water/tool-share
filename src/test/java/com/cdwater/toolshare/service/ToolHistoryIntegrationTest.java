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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * 管理端工具历史租赁：#10 按工具分页、归属正确、内容完整，用户端不暴露他人租赁。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-HISTORY-";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolLogMapper toolLogMapper;

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
    void admin_tool_history_pages_and_only_contains_that_tools_rentals() {
        String admin = adminLogin();
        long toolId = createApprovedTool(admin, "main");
        long otherToolId = createApprovedTool(admin, "other");

        LocalDateTime now = LocalDateTime.now().withNano(0);
        long first = insertRental(toolId, "reserved", "RESERVED", "张三", "13900000081",
                now.plusDays(1), now.plusDays(2), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        long second = insertRental(toolId, "returned", "RETURNED", "李四", "13900000082",
                now.minusDays(3), now.minusDays(2), "MINOR",
                new BigDecimal("12.50"), new BigDecimal("8.00"));
        long third = insertRental(toolId, "cancelled", "CANCELLED", "王五", "13900000083",
                now.plusDays(5), now.plusDays(6), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        long foreign = insertRental(otherToolId, "foreign", "RETURNED", "赵六", "13900000084",
                now.minusDays(1), now, "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);

        JsonNode pageOne = ok(call(HttpMethod.GET,
                "/api/admin/tools/" + toolId + "/rentals?page=1&size=2", admin, null));
        JsonNode data = pageOne.get("data");
        assertEquals(3, data.get("total").asInt(), data.toString());
        assertEquals(2, data.get("records").size(), data.toString());
        assertEquals(1, data.get("page").asLong());
        assertEquals(2, data.get("size").asLong());
        for (JsonNode record : data.get("records")) {
            assertEquals(toolId, record.get("toolId").asLong(), record.toString());
        }

        JsonNode pageTwo = ok(call(HttpMethod.GET,
                "/api/admin/tools/" + toolId + "/rentals?page=2&size=2", admin, null));
        assertEquals(1, pageTwo.get("data").get("records").size());
        for (JsonNode record : pageTwo.get("data").get("records")) {
            assertEquals(toolId, record.get("toolId").asLong(), record.toString());
        }

        List<Long> seen = new ArrayList<>(ids(pageOne));
        seen.addAll(ids(pageTwo));
        assertTrue(seen.containsAll(List.of(first, second, third)), seen.toString());
        assertFalse(seen.contains(foreign), "不得混入其他工具的租赁记录");

        JsonNode returned = recordWithOrderNo(pageOne, pageTwo, rentalMapper.selectById(second).getOrderNo());
        assertEquals("李四", returned.get("renterName").asString());
        assertEquals("RETURNED", returned.get("status").asString());
        assertEquals("轻微损坏", returned.get("damageResultName").asString());
        assertEquals(0, returned.get("overdueFee").decimalValue()
                .compareTo(new BigDecimal("12.50")), returned.toString());
        assertEquals(0, returned.get("damageFee").decimalValue()
                .compareTo(new BigDecimal("8.00")), returned.toString());
        assertTrue(returned.hasNonNull("startTime"), returned.toString());
        assertTrue(returned.hasNonNull("endTime"), returned.toString());
    }

    @Test
    void user_tool_detail_does_not_expose_rental_history() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "user-visible");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertRental(toolId, "hidden", "RETURNED", "隐私测试", "13900000085",
                now.minusDays(2), now.minusDays(1), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);

        JsonNode detail = ok(call(HttpMethod.GET, "/api/user/tools/" + toolId, user, null));
        assertFalse(detail.get("data").has("rentals"), detail.toString());
        assertFalse(detail.get("data").has("rentalRecords"), detail.toString());
    }

    @Test
    void user_tool_history_returns_completed_windows_without_personal_data() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "user-history");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertRental(toolId, "done-1", "RETURNED", "张三", "13900000091",
                now.minusDays(4), now.minusDays(3), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);
        insertRental(toolId, "done-2", "RETURNED", "李四", "13900000092",
                now.minusDays(2), now.minusDays(1), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);
        insertRental(toolId, "active", "RESERVED", "王五", "13900000093",
                now.plusDays(1), now.plusDays(2), null, BigDecimal.ZERO, BigDecimal.ZERO);

        JsonNode data = ok(call(HttpMethod.GET,
                "/api/user/tools/" + toolId + "/history", user, null)).get("data");

        assertEquals(toolId, data.get("toolId").asLong());
        assertEquals(2, data.get("completedCount").asLong(), "只统计已归还的租赁单");
        assertEquals(2, data.get("windows").size(), data.toString());
        for (JsonNode window : data.get("windows")) {
            assertTrue(window.hasNonNull("startTime"), data.toString());
            assertTrue(window.hasNonNull("endTime"), data.toString());
            assertFalse(window.has("renterName"), "不得暴露他人姓名");
            assertFalse(window.has("renterPhone"), "不得暴露他人手机号");
            assertFalse(window.has("orderNo"), "不得暴露他人单号");
        }
    }

    @Test
    void unknown_tool_history_is_not_found() {
        String admin = adminLogin();
        ApiResponse response = call(HttpMethod.GET,
                "/api/admin/tools/99999999/rentals", admin, null);
        assertEquals(404, response.status(), response.body().toString());
        assertEquals("C404", response.body().get("code").asString());
    }

    private JsonNode recordWithOrderNo(JsonNode pageOne, JsonNode pageTwo, String orderNo) {
        for (JsonNode page : List.of(pageOne, pageTwo)) {
            for (JsonNode record : page.get("data").get("records")) {
                if (orderNo.equals(record.get("orderNo").asString())) {
                    return record;
                }
            }
        }
        throw new AssertionError("未找到租赁单 " + orderNo);
    }

    private List<Long> ids(JsonNode response) {
        List<Long> result = new ArrayList<>();
        for (JsonNode record : response.get("data").get("records")) {
            result.add(record.get("id").asLong());
        }
        return result;
    }

    private long insertRental(long toolId, String suffix, String status, String renterName,
                              String renterPhone, LocalDateTime start, LocalDateTime end,
                              String damageResult, BigDecimal overdueFee, BigDecimal damageFee) {
        Rental rental = new Rental();
        rental.setOrderNo("IT-HIS-" + SEQ.incrementAndGet() + "-" + suffix);
        rental.setToolId(toolId);
        rental.setToolCode("IT-HIS-CODE-" + suffix);
        rental.setToolName("历史租赁测试工具 " + suffix);
        rental.setUserId(1L);
        rental.setRenterName(renterName);
        rental.setRenterPhone(renterPhone);
        rental.setStartTime(start);
        rental.setEndTime(end);
        rental.setPickupPerson(renterName);
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        rental.setStatus(status);
        rental.setActualReturnTime("RETURNED".equals(status) ? end.plusMinutes(30) : null);
        rental.setOverdueFee(overdueFee);
        rental.setDamageFee(damageFee);
        rental.setRefundAmount(BigDecimal.ZERO);
        rental.setDamageResult(damageResult);
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    private long createApprovedTool(String admin, String suffix) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", new BigDecimal("100"),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("data").get("id").asLong();
        toolIds.add(id);
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")));
        return id;
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
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
