package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.credit.CreditService;
import com.cdwater.toolshare.module.credit.UserCreditEvent;
import com.cdwater.toolshare.module.credit.UserCreditEventMapper;
import com.cdwater.toolshare.module.rental.PaymentRecord;
import com.cdwater.toolshare.module.rental.PaymentRecordMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalFee;
import com.cdwater.toolshare.module.rental.RentalFeeMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolReview;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信用分：#25 初始 100、按时 +2、逾期 -3、严重损坏 -10、低星评价 -5，0–100 裁剪，
 * 流水与分数一致、同一单据重复触发不重复计分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.auth.expose-captcha=true")
@Import(TestStorageConfig.class)
class CreditIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-CREDIT-";

    @LocalServerPort
    int port;

    @Autowired
    UserMapper userMapper;
    @Autowired
    UserCreditEventMapper eventMapper;
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
    ToolReviewMapper reviewMapper;
    @Autowired
    CreditService creditService;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 各测试独立：seed 用户 1/2 的分数与流水归零（其他测试类也会触发信用事件）
        for (long userId : List.of(1L, 2L)) {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getCreditScore, 100));
            eventMapper.delete(new LambdaQueryWrapper<UserCreditEvent>()
                    .eq(UserCreditEvent::getUserId, userId));
        }
    }

    @AfterEach
    void cleanUp() {
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        for (Tool tool : tools) {
            List<Rental> rentals = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            for (Rental rental : rentals) {
                reviewMapper.delete(new LambdaQueryWrapper<ToolReview>()
                        .eq(ToolReview::getRentalId, rental.getId()));
                rentalFeeMapper.delete(new LambdaQueryWrapper<RentalFee>()
                        .eq(RentalFee::getRentalId, rental.getId()));
                paymentRecordMapper.delete(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getRentalId, rental.getId()));
                eventMapper.delete(new LambdaQueryWrapper<UserCreditEvent>()
                        .eq(UserCreditEvent::getRentalId, rental.getId()));
                rentalMapper.deleteById(rental.getId());
            }
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            toolMapper.deleteById(tool.getId());
        }
    }

    @Test
    void initial_credit_is_100_with_no_events_and_requires_login() {
        String user = userLogin();
        JsonNode data = credit(user);
        assertEquals(100, data.get("score").asInt(), data.toString());
        assertEquals(0, data.get("events").get("total").asInt(), data.toString());
        assertEquals(0, data.get("events").get("records").size(), data.toString());

        ApiResponse anonymous = call(HttpMethod.GET, "/api/user/credit", null, null);
        assertEquals(401, anonymous.status(), anonymous.body().toString());
    }

    @Test
    void on_time_return_adds_two_and_records_event() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "on-time");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        JsonNode data = credit(user);
        assertEquals(92, data.get("score").asInt(), data.toString());
        JsonNode event = onlyEvent(data, "ON_TIME_RETURN");
        assertEquals(2, event.get("delta").asInt(), event.toString());
        assertEquals(rentalId, event.get("rentalId").asLong(), event.toString());
        assertFalse(event.get("reason").asString().isBlank(), event.toString());
    }

    @Test
    void overdue_return_deducts_three() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "overdue");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(4), now.minusHours(3));

        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        JsonNode data = credit(user);
        assertEquals(87, data.get("score").asInt(), data.toString());
        JsonNode event = onlyEvent(data, "OVERDUE");
        assertEquals(-3, event.get("delta").asInt(), event.toString());
        assertEquals(rentalId, event.get("rentalId").asLong(), event.toString());
        assertEquals(0, countEvents(data, "ON_TIME_RETURN"), data.toString());
    }

    @Test
    void severe_damage_deducts_ten_alongside_return_rule() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "severe");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        ok(returnRental(admin, rentalId, now, "SEVERE", Map.of()));

        JsonNode data = credit(user);
        assertEquals(82, data.get("score").asInt(), data.toString());
        assertEquals(2, onlyEvent(data, "ON_TIME_RETURN").get("delta").asInt(),
                data.toString());
        assertEquals(-10, onlyEvent(data, "SEVERE_DAMAGE").get("delta").asInt(),
                data.toString());
    }

    @Test
    void low_review_deducts_five_only_for_two_stars_or_less() {
        String admin = adminLogin();
        String user = userLogin();
        long first = pickedUpRental(admin, user, "low-review");
        long second = pickedUpRental(admin, user, "normal-review");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(first, now.minusHours(3), now.plusHours(1));
        setSlot(second, now.minusHours(3), now.plusHours(1));
        ok(returnRental(admin, first, now, "INTACT", Map.of()));
        ok(returnRental(admin, second, now, "INTACT", Map.of()));

        JsonNode review = ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", first, "rating", 2, "content", "不太满意")));
        long reviewId = review.get("data").get("id").asLong();
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", second, "rating", 3, "content", "一般")));

        JsonNode data = credit(user);
        // 90 + 2 + 2 - 5（仅 2 星评价扣分）
        assertEquals(89, data.get("score").asInt(), data.toString());
        JsonNode event = onlyEvent(data, "LOW_REVIEW");
        assertEquals(-5, event.get("delta").asInt(), event.toString());
        assertEquals(reviewId, event.get("reviewId").asLong(), event.toString());
        assertEquals(first, event.get("rentalId").asLong(), event.toString());
    }

    @Test
    void score_is_clamped_between_zero_and_hundred() {
        String admin = adminLogin();
        String user = userLogin();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        long floorRental = pickedUpRental(admin, user, "floor");
        setScore(1L, 1);
        setSlot(floorRental, now.minusHours(4), now.minusHours(3));
        ok(returnRental(admin, floorRental, now, "INTACT", Map.of()));

        JsonNode floored = credit(user);
        assertEquals(0, floored.get("score").asInt(), floored.toString());
        assertEquals(-1, onlyEvent(floored, "OVERDUE").get("delta").asInt(),
                floored.toString());

        long capRental = pickedUpRental(admin, user, "cap");
        setScore(1L, 99);
        setSlot(capRental, now.minusHours(3), now.plusHours(1));
        ok(returnRental(admin, capRental, now, "INTACT", Map.of()));

        JsonNode capped = credit(user);
        assertEquals(100, capped.get("score").asInt(), capped.toString());
        assertEquals(1, onlyEvent(capped, "ON_TIME_RETURN").get("delta").asInt(),
                capped.toString());
    }

    @Test
    void duplicate_return_and_review_do_not_double_count() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "duplicate");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));
        ApiResponse duplicateReturn = returnRental(admin, rentalId, now, "INTACT", Map.of());
        assertEquals(409, duplicateReturn.status(), duplicateReturn.body().toString());

        JsonNode afterReturn = credit(user);
        assertEquals(92, afterReturn.get("score").asInt(), afterReturn.toString());
        assertEquals(1, countEvents(afterReturn, "ON_TIME_RETURN"), afterReturn.toString());

        Map<String, Object> review = Map.of(
                "rentalId", rentalId, "rating", 1, "content", "差评");
        ok(call(HttpMethod.POST, "/api/user/reviews", user, review));
        ApiResponse duplicateReview = call(HttpMethod.POST, "/api/user/reviews", user, review);
        assertEquals(409, duplicateReview.status(), duplicateReview.body().toString());

        JsonNode afterReview = credit(user);
        assertEquals(87, afterReview.get("score").asInt(), afterReview.toString());
        assertEquals(1, countEvents(afterReview, "LOW_REVIEW"), afterReview.toString());
    }

    @Test
    void unique_constraint_makes_repeated_events_idempotent() {
        String user = userLogin();
        setScore(1L, 90);
        // 模拟同一租赁/评价被直接重复触发，唯一键必须挡住第二次
        creditService.recordReturn(1L, 900_000_001L, "IT-CREDIT-DUP", true, false);
        creditService.recordReturn(1L, 900_000_001L, "IT-CREDIT-DUP", true, false);
        creditService.recordReview(1L, 900_000_001L, 900_000_002L, 1);
        creditService.recordReview(1L, 900_000_001L, 900_000_002L, 1);

        JsonNode data = credit(user);
        assertEquals(87, data.get("score").asInt(), data.toString());
        assertEquals(1, countEvents(data, "ON_TIME_RETURN"), data.toString());
        assertEquals(1, countEvents(data, "LOW_REVIEW"), data.toString());
    }

    @Test
    void ledger_sum_matches_score_and_is_paginated() {
        String admin = adminLogin();
        String user = userLogin();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        long onTime = pickedUpRental(admin, user, "ledger-on-time");
        setSlot(onTime, now.minusHours(3), now.plusHours(1));
        ok(returnRental(admin, onTime, now, "INTACT", Map.of())); // 100 +0（封顶）
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", onTime, "rating", 1))); // -5 => 95

        long overdue = pickedUpRental(admin, user, "ledger-overdue");
        setSlot(overdue, now.minusHours(4), now.minusHours(3));
        ok(returnRental(admin, overdue, now, "INTACT", Map.of())); // -3 => 92
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", overdue, "rating", 2))); // -5 => 87

        long severe = pickedUpRental(admin, user, "ledger-severe");
        setSlot(severe, now.minusHours(3), now.plusHours(1));
        ok(returnRental(admin, severe, now, "SEVERE", Map.of())); // +2 -10 => 79

        JsonNode firstPage = ok(call(HttpMethod.GET,
                "/api/user/credit?page=1&size=2", user, null)).get("data");
        assertEquals(79, firstPage.get("score").asInt(), firstPage.toString());
        assertEquals(6, firstPage.get("events").get("total").asInt(), firstPage.toString());
        assertEquals(2, firstPage.get("events").get("records").size(), firstPage.toString());

        JsonNode lastPage = ok(call(HttpMethod.GET,
                "/api/user/credit?page=3&size=2", user, null)).get("data");
        assertEquals(2, lastPage.get("events").get("records").size(), lastPage.toString());

        JsonNode all = ok(call(HttpMethod.GET,
                "/api/user/credit?page=1&size=100", user, null)).get("data");
        int sum = 0;
        for (JsonNode event : all.get("events").get("records")) {
            sum += event.get("delta").asInt();
        }
        assertEquals(all.get("score").asInt() - 100, sum, all.toString());
    }

    @Test
    void credit_is_scoped_to_the_logged_in_user() {
        String admin = adminLogin();
        String user = userLogin();
        long rentalId = pickedUpRental(admin, user, "scoped");
        setScore(1L, 90);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        String other = loginByCode("13800000002");
        JsonNode otherCredit = credit(other);
        assertEquals(100, otherCredit.get("score").asInt(), otherCredit.toString());
        assertEquals(0, otherCredit.get("events").get("total").asInt(), otherCredit.toString());

        // 无法通过参数读取他人分数：查询始终落在登录用户
        JsonNode spoofed = ok(call(HttpMethod.GET,
                "/api/user/credit?userId=2", user, null)).get("data");
        assertEquals(92, spoofed.get("score").asInt(), spoofed.toString());
        assertEquals(1, spoofed.get("events").get("total").asInt(), spoofed.toString());
    }

    private JsonNode credit(String token) {
        return ok(call(HttpMethod.GET, "/api/user/credit?page=1&size=10", token, null))
                .get("data");
    }

    private JsonNode onlyEvent(JsonNode data, String type) {
        JsonNode found = null;
        for (JsonNode event : data.get("events").get("records")) {
            if (type.equals(event.get("eventType").asString())) {
                assertTrue(found == null, type + " 应只有一条：" + data);
                found = event;
            }
        }
        assertTrue(found != null, "缺少 " + type + " 事件：" + data);
        return found;
    }

    private int countEvents(JsonNode data, String type) {
        int count = 0;
        for (JsonNode event : data.get("events").get("records")) {
            if (type.equals(event.get("eventType").asString())) {
                count++;
            }
        }
        return count;
    }

    private void setScore(long userId, int score) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getCreditScore, score));
    }

    private long pickedUpRental(String admin, String user, String suffix) {
        long toolId = createApprovedTool(admin, suffix);
        long rentalId = createRental(user, toolId);
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        return rentalId;
    }

    private long createApprovedTool(String admin, String suffix) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "信用测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", new BigDecimal("100"),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("data").get("id").asLong();
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    private long createRental(String userToken, long toolId) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "信用测试居民")));
        return created.get("data").get("id").asLong();
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

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    private String userLogin() {
        return login("/api/user/login/by-pwd",
                Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    private String loginByCode(String phone) {
        String code = ok(call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", phone))).get("data").asString();
        return login("/api/user/login/by-code", Map.of("phone", phone, "code", code));
    }

    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
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
