package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.PaymentRecord;
import com.cdwater.toolshare.module.rental.PaymentRecordMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalFee;
import com.cdwater.toolshare.module.rental.RentalFeeMapper;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
 * 结算流水与归还验收：金额只由服务端计算，减免/调整带原因落 WAIVER 流水，
 * 超出押金的赔偿形成应收，流水与 t_rental 展示快照一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalSettlementIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-SETTLE-";

    @LocalServerPort
    int port;

    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolLogMapper toolLogMapper;
    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    RentalFeeMapper rentalFeeMapper;
    @Autowired
    PaymentRecordMapper paymentRecordMapper;
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
            List<Rental> rentals = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            for (Rental rental : rentals) {
                rentalFeeMapper.delete(new LambdaQueryWrapper<RentalFee>()
                        .eq(RentalFee::getRentalId, rental.getId()));
                paymentRecordMapper.delete(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getRentalId, rental.getId()));
            }
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
    void on_time_intact_return_refunds_full_deposit_and_writes_ledger() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "on-time", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        assertEquals(200, response.status(), response.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals("RETURNED", rental.getStatus());
        assertEquals("REFUNDED", rental.getDepositStatus());
        assertEquals(0, rental.getOverdueFee().signum());
        assertEquals(0, rental.getDamageFee().signum());
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(0, rental.getActualReturnTime().compareTo(now));

        assertEquals(0, fees(rentalId).size());
        List<PaymentRecord> payments = payments(rentalId);
        assertEquals(2, payments.size(), payments.toString());
        assertPayment(payments, "DEPOSIT_IN", "100.00");
        assertPayment(payments, "DEPOSIT_REFUND", "100.00");
        assertEquals(0, receivable(rentalId).signum());
    }

    @Test
    void one_minute_late_rounds_up_to_one_hour() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "round-up", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        assertEquals(200, response.status(), response.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("95.00")));
        assertFee(rentalId, "OVERDUE", "5.00");
        assertPayment(payments(rentalId), "DEPOSIT_REFUND", "95.00");
    }

    @Test
    void overdue_fee_is_capped_at_deposit() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "cap", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusDays(3), now.minusHours(30));

        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        assertEquals(200, response.status(), response.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("100.00")));
        assertEquals(0, rental.getRefundAmount().signum());
        assertFee(rentalId, "OVERDUE", "100.00");
        assertEquals(0, payments(rentalId).stream()
                .filter(p -> "DEPOSIT_REFUND".equals(p.getRecordType())).count());
    }

    @Test
    void severe_damage_defaults_to_50_and_excess_becomes_receivable() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "receivable", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusDays(3), now.minusHours(30));

        ApiResponse response = returnRental(admin, rentalId, now, "SEVERE", Map.of());
        assertEquals(200, response.status(), response.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("100.00")));
        assertEquals(0, rental.getDamageFee().compareTo(new BigDecimal("50.00")));
        assertEquals(0, rental.getRefundAmount().signum());
        assertFee(rentalId, "OVERDUE", "100.00");
        assertFee(rentalId, "DAMAGE", "50.00");
        assertPayment(payments(rentalId), "RECEIVABLE", "50.00");
    }

    @Test
    void waiver_requires_reason_and_is_recorded_as_waiver_ledger() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "waiver", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        ApiResponse noReason = returnRental(admin, rentalId, now, "SEVERE",
                Map.of("waiveOverdue", true));
        assertEquals(400, noReason.status(), noReason.body().toString());
        assertEquals("C400", code(noReason));
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());

        ApiResponse waived = returnRental(admin, rentalId, now, "SEVERE",
                Map.of("waiveOverdue", true, "waiveDamage", true,
                        "waiverReason", "社区活动减免"));
        assertEquals(200, waived.status(), waived.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        // 计算金额保持原值，减免单独落流水
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        assertEquals(0, rental.getDamageFee().compareTo(new BigDecimal("50.00")));
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("100.00")));
        BigDecimal waivedTotal = fees(rentalId).stream()
                .filter(f -> "WAIVER".equals(f.getFeeType()))
                .map(RentalFee::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, waivedTotal.compareTo(new BigDecimal("55.00")));
        assertPayment(payments(rentalId), "DEPOSIT_REFUND", "100.00");
    }

    @Test
    void return_time_must_not_be_in_future_or_before_rental_start() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "time-guard", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(1), now.plusHours(1));

        ApiResponse future = returnRental(admin, rentalId, now.plusHours(2), "INTACT", Map.of());
        assertEquals(422, future.status(), future.body().toString());

        ApiResponse beforeStart = returnRental(admin, rentalId, now.minusHours(2), "INTACT", Map.of());
        assertEquals(400, beforeStart.status(), beforeStart.body().toString());
        assertTrue(beforeStart.body().get("message").asString().contains("开始"),
                beforeStart.body().toString());
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());
    }

    @Test
    void rental_detail_exposes_fee_and_payment_ledger() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "ledger", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));
        assertEquals(200, returnRental(admin, rentalId, now, "INTACT", Map.of()).status());

        ApiResponse detail = call(HttpMethod.GET, "/api/user/rentals/" + rentalId, user, null);
        assertEquals(200, detail.status(), detail.body().toString());
        JsonNode data = detail.body().get("data");

        assertEquals(1, data.get("fees").size(), data.toString());
        assertEquals("OVERDUE", data.get("fees").get(0).get("feeType").asString());
        assertEquals("逾期费", data.get("fees").get(0).get("feeTypeName").asString());
        assertEquals(0, data.get("fees").get(0).get("amount").decimalValue()
                .compareTo(new BigDecimal("5.00")));

        assertEquals(2, data.get("payments").size(), data.toString());
        assertEquals("DEPOSIT_IN", data.get("payments").get(0).get("recordType").asString());
        assertEquals("DEPOSIT_REFUND", data.get("payments").get(1).get("recordType").asString());
        assertEquals("退还押金", data.get("payments").get(1).get("recordTypeName").asString());
    }

    @Test
    void unfinished_report_requires_explicit_damage_return() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "open-report", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        assertEquals(200, call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                "rentalId", rentalId, "damagePart", "电机", "description", "异响",
                "images", List.of())).status());

        ApiResponse rejected = returnRental(admin, rentalId, now, "INTACT", Map.of());
        assertEquals(409, rejected.status(), rejected.body().toString());
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());

        ApiResponse accepted = returnRental(admin, rentalId, now, "INTACT",
                Map.of("returnWithDamage", true));
        assertEquals(200, accepted.status(), accepted.body().toString());
        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals("RETURNED", rental.getStatus());
        assertEquals("存在未终结报修，带伤归还", rental.getReturnRemark());
        assertEquals("MAINTENANCE",
                toolMapper.selectById(rental.getToolId()).getStatus(),
                "带伤归还时工具保持维修中");
    }

    @Test
    void client_supplied_amounts_are_ignored() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "ignore-client", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        ApiResponse response = returnRental(admin, rentalId, now, "INTACT",
                Map.of("overdueFee", 0, "damageFee", 0));
        assertEquals(200, response.status(), response.body().toString());

        Rental rental = rentalMapper.selectById(rentalId);
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("95.00")));
    }

    @Test
    void concurrent_return_only_one_succeeds_and_ledger_written_once() throws Exception {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "concurrent", "100");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        List<ApiResponse> results = concurrently(
                () -> returnRental(admin, rentalId, now, "INTACT", Map.of()),
                () -> returnRental(admin, rentalId, now, "INTACT", Map.of()));

        assertEquals(1, results.stream().filter(r -> r.status() == 200).count(), results.toString());
        assertEquals(1, results.stream().filter(r -> r.status() == 409).count(), results.toString());
        assertEquals("RETURNED", rentalMapper.selectById(rentalId).getStatus());
        assertEquals(1, payments(rentalId).stream()
                .filter(p -> "DEPOSIT_REFUND".equals(p.getRecordType())).count());
    }

    private long pickedUpRental(String admin, String user, String suffix, String deposit) {
        long toolId = createApprovedTool(admin, suffix, deposit);
        long rentalId = createRental(user, toolId);
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        return rentalId;
    }

    private void setSlot(long rentalId, LocalDateTime start, LocalDateTime end) {
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                .eq(Rental::getId, rentalId)
                .set(Rental::getStartTime, start)
                .set(Rental::getEndTime, end));
    }

    private ApiResponse returnRental(String admin, long rentalId, LocalDateTime actualReturnTime,
                                     String damageResult, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("actualReturnTime", actualReturnTime.format(FORMAT));
        body.put("damageResult", damageResult);
        return call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return", admin, body);
    }

    private List<RentalFee> fees(long rentalId) {
        return rentalFeeMapper.selectList(new LambdaQueryWrapper<RentalFee>()
                .eq(RentalFee::getRentalId, rentalId));
    }

    private List<PaymentRecord> payments(long rentalId) {
        return paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getRentalId, rentalId));
    }

    private BigDecimal receivable(long rentalId) {
        return payments(rentalId).stream()
                .filter(p -> "RECEIVABLE".equals(p.getRecordType()))
                .map(PaymentRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertFee(long rentalId, String type, String amount) {
        List<RentalFee> matches = fees(rentalId).stream()
                .filter(f -> type.equals(f.getFeeType())).toList();
        assertEquals(1, matches.size(), type + " 流水应只有一条");
        assertEquals(0, matches.get(0).getAmount().compareTo(new BigDecimal(amount)));
    }

    private void assertPayment(List<PaymentRecord> payments, String type, String amount) {
        List<PaymentRecord> matches = payments.stream()
                .filter(p -> type.equals(p.getRecordType())).toList();
        assertEquals(1, matches.size(), type + " 流水应只有一条");
        assertEquals(0, matches.get(0).getAmount().compareTo(new BigDecimal(amount)));
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

    private long createRental(String userToken, long toolId) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国")));
        return created.get("data").get("id").asLong();
    }

    private long createApprovedTool(String admin, String suffix, String deposit) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", new BigDecimal(deposit),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("data").get("id").asLong();
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    private String code(ApiResponse response) {
        return response.body().get("code").asString();
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
