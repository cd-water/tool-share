package com.cdwater.toolshare.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalExportRow;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.shared.security.IdCardCipher;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租赁查询：#13 服务端分页/筛选、身份证脱敏快照、录入与更新人、Excel 导出。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalQueryIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String TOOL_PREFIX = "IT-QUERY-";
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
    UserMapper userMapper;
    @Autowired
    IdCardCipher idCardCipher;

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
    void create_rental_snapshots_masked_id_card_and_operator_names() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "snapshot");
        long rentalId = createRental(user, toolId);
        rentalIds.add(rentalId);

        User resident = userMapper.selectById(1L);
        Rental created = rentalMapper.selectById(rentalId);
        assertEquals(idCardCipher.mask(resident.getIdCard()), created.getRenterIdCardMasked());
        assertFalse(created.getRenterIdCardMasked().contains("310101199001011234"),
                "快照不得保存完整身份证");
        assertEquals("王建国", created.getCreatedByName());
        assertEquals("王建国", created.getUpdatedByName());

        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        ApiResponse returned = call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return",
                admin, Map.of("actualReturnTime", now.format(FORMAT), "damageResult", "INTACT"));
        assertEquals(200, returned.status(), returned.body().toString());
        assertEquals("admin", rentalMapper.selectById(rentalId).getUpdatedByName());

        long cancelToolId = createApprovedTool(admin, "cancel");
        long cancelRentalId = createRental(user, cancelToolId);
        rentalIds.add(cancelRentalId);
        ApiResponse cancelled = call(HttpMethod.POST, "/api/user/rentals/" + cancelRentalId + "/cancel",
                user, Map.of("reason", "行程变化"));
        assertEquals(200, cancelled.status(), cancelled.body().toString());
        assertEquals("王建国", rentalMapper.selectById(cancelRentalId).getUpdatedByName());
    }

    @Test
    void admin_filters_overdue_tristate_damage_result_and_keyword() {
        String admin = adminLogin();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long overdue = insertRental("overdue", "OVERDUE", "张三", "13900000009",
                now.minusDays(2), now.minusHours(2), null);
        long pastPickedUp = insertRental("past-picked", "PICKED_UP", "Query Person", "13900000010",
                now.minusDays(2), now.minusHours(1), null);
        long futurePickedUp = insertRental("future-picked", "PICKED_UP", "Query Person", "13900000011",
                now.minusHours(1), now.plusDays(2), null);
        long severe = insertRental("severe", "RETURNED", "Query Person", "13900000012",
                now.minusDays(2), now.minusHours(1), "SEVERE");
        long minor = insertRental("minor", "RETURNED", "Query Person", "13900000013",
                now.minusDays(2), now.minusHours(1), "MINOR");

        List<Long> onlyOverdue = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?overdue=true&size=100", admin, null)));
        assertTrue(onlyOverdue.contains(overdue), onlyOverdue.toString());
        assertTrue(onlyOverdue.contains(pastPickedUp), onlyOverdue.toString());
        assertFalse(onlyOverdue.contains(futurePickedUp), onlyOverdue.toString());
        assertFalse(onlyOverdue.contains(severe), onlyOverdue.toString());

        List<Long> notOverdue = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?overdue=false&size=100", admin, null)));
        assertFalse(notOverdue.contains(overdue), notOverdue.toString());
        assertFalse(notOverdue.contains(pastPickedUp), notOverdue.toString());
        assertTrue(notOverdue.contains(futurePickedUp), notOverdue.toString());
        assertTrue(notOverdue.contains(severe), notOverdue.toString());
        assertTrue(notOverdue.contains(minor), notOverdue.toString());

        List<Long> all = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?overdue=all&size=100", admin, null)));
        assertTrue(all.containsAll(List.of(overdue, pastPickedUp, futurePickedUp, severe, minor)),
                all.toString());

        List<Long> severeOnly = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?damageResult=SEVERE&size=100", admin, null)));
        assertTrue(severeOnly.contains(severe), severeOnly.toString());
        assertFalse(severeOnly.contains(minor), severeOnly.toString());

        List<Long> returnedMinor = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?status=RETURNED&damageResult=MINOR&size=100", admin, null)));
        assertTrue(returnedMinor.contains(minor), returnedMinor.toString());
        assertFalse(returnedMinor.contains(severe), returnedMinor.toString());

        List<Long> byOrderNo = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?keyword=" + rentalMapper.selectById(severe).getOrderNo()
                        + "&size=100", admin, null)));
        assertTrue(byOrderNo.contains(severe), byOrderNo.toString());

        List<Long> byPhone = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?keyword=13900000009&size=100", admin, null)));
        assertTrue(byPhone.contains(overdue), byPhone.toString());
    }

    @Test
    void user_page_paginates_and_filters_by_status() {
        String user = userLogin();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long first = insertRental("u-reserved-1", "RESERVED", "Paginate User", "13900000021",
                now.plusDays(1), now.plusDays(2), null);
        long second = insertRental("u-reserved-2", "RESERVED", "Paginate User", "13900000022",
                now.plusDays(3), now.plusDays(4), null);
        long third = insertRental("u-returned", "RETURNED", "Paginate User", "13900000023",
                now.minusDays(2), now.minusDays(1), "INTACT");

        JsonNode pageOne = ok(call(HttpMethod.GET, "/api/user/rentals?page=1&size=2", user, null));
        assertEquals(2, pageOne.get("data").get("records").size());
        assertEquals(2, pageOne.get("data").get("size").asLong());
        JsonNode pageTwo = ok(call(HttpMethod.GET, "/api/user/rentals?page=2&size=2", user, null));
        List<Long> seen = new ArrayList<>(ids(pageOne));
        seen.addAll(ids(pageTwo));
        assertTrue(seen.contains(first) && seen.contains(second) && seen.contains(third),
                seen.toString());

        JsonNode reserved = ok(call(HttpMethod.GET,
                "/api/user/rentals?status=RESERVED&size=100", user, null));
        for (JsonNode record : reserved.get("data").get("records")) {
            assertEquals("RESERVED", record.get("status").asString());
        }
        assertTrue(ids(reserved).containsAll(List.of(first, second)));

        JsonNode clamped = ok(call(HttpMethod.GET, "/api/user/rentals?size=200", user, null));
        assertEquals(100, clamped.get("data").get("size").asLong(),
                "前端请求上限应与后端一致，后端返回实际 size");
    }

    @Test
    void export_contains_snapshot_fields_and_respects_damage_filter() {
        String admin = adminLogin();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long severe = insertRental("export-severe", "RETURNED", "Export Person", "13900000031",
                now.minusDays(3), now.minusHours(5), "SEVERE");
        long intact = insertRental("export-intact", "RETURNED", "Export Person", "13900000032",
                now.minusDays(2), now.minusHours(2), "INTACT");

        byte[] allBytes = export(admin, "");
        List<RentalExportRow> allRows = readRows(allBytes);
        RentalExportRow severeRow = allRows.stream()
                .filter(row -> row.getOrderNo().equals(rentalMapper.selectById(severe).getOrderNo()))
                .findFirst().orElseThrow();
        assertEquals("3101********1234", severeRow.getRenterIdCardMasked());
        assertEquals("录入测试", severeRow.getCreatedByName());
        assertEquals("更新测试", severeRow.getUpdatedByName());
        assertEquals("严重损坏", severeRow.getDamageResultName());
        assertEquals("50.00", severeRow.getOverdueFee());
        assertEquals("50.00", severeRow.getDamageFee());
        assertEquals("0.00", severeRow.getRefundAmount());

        List<RentalExportRow> filtered = readRows(export(admin, "?damageResult=SEVERE"));
        assertTrue(filtered.stream().anyMatch(row -> row.getOrderNo()
                .equals(rentalMapper.selectById(severe).getOrderNo())));
        assertFalse(filtered.stream().anyMatch(row -> row.getOrderNo()
                .equals(rentalMapper.selectById(intact).getOrderNo())));
    }

    private long insertRental(String suffix, String status, String renterName, String renterPhone,
                              LocalDateTime start, LocalDateTime end, String damageResult) {
        return insertRental(suffix, status, renterName, renterPhone, start, end, damageResult,
                "3101********1234", "录入测试", "更新测试");
    }

    private long insertRental(String suffix, String status, String renterName, String renterPhone,
                              LocalDateTime start, LocalDateTime end, String damageResult,
                              String idCardMasked, String createdBy, String updatedBy) {
        Rental rental = new Rental();
        rental.setOrderNo("IT-QRY-" + SEQ.incrementAndGet() + "-" + suffix);
        rental.setToolId(1L);
        rental.setToolCode("IT-QRY-CODE-" + suffix);
        rental.setToolName("查询测试工具 " + suffix);
        rental.setUserId(1L);
        rental.setRenterName(renterName);
        rental.setRenterPhone(renterPhone);
        rental.setRenterIdCardMasked(idCardMasked);
        rental.setCreatedByName(createdBy);
        rental.setUpdatedByName(updatedBy);
        rental.setStartTime(start);
        rental.setEndTime(end);
        rental.setPickupPerson(renterName);
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        rental.setStatus(status);
        rental.setActualReturnTime("RETURNED".equals(status) ? end.plusMinutes(30) : null);
        rental.setOverdueFee("RETURNED".equals(status) ? new BigDecimal("50.00") : BigDecimal.ZERO);
        rental.setDamageFee("SEVERE".equals(damageResult) ? new BigDecimal("50.00") : BigDecimal.ZERO);
        rental.setRefundAmount("RETURNED".equals(status) ? BigDecimal.ZERO : BigDecimal.ZERO);
        rental.setDamageResult(damageResult);
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    private void setSlot(long rentalId, LocalDateTime start, LocalDateTime end) {
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                .eq(Rental::getId, rentalId)
                .set(Rental::getStartTime, start)
                .set(Rental::getEndTime, end));
    }

    private byte[] export(String admin, String query) {
        return client.method(HttpMethod.GET).uri("/api/admin/rentals/export" + query)
                .header("Authorization", "Bearer " + admin)
                .exchange((request, response) -> {
                    assertEquals(200, response.getStatusCode().value());
                    return response.getBody().readAllBytes();
                });
    }

    private List<RentalExportRow> readRows(byte[] bytes) {
        return EasyExcel.read(new ByteArrayInputStream(bytes))
                .head(RentalExportRow.class).sheet().doReadSync();
    }

    private List<Long> ids(JsonNode body) {
        List<Long> result = new ArrayList<>();
        for (JsonNode record : body.get("data").get("records")) {
            result.add(record.get("id").asLong());
        }
        return result;
    }

    private long createRental(String userToken, long toolId) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国")));
        return created.get("data").get("id").asLong();
    }

    private long createApprovedTool(String admin, String suffix) {
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
        long id = created.get("data").get("id").asLong();
        toolIds.add(id);
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
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
