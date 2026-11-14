package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolReview;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
import com.cdwater.toolshare.module.tool.Tool;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评价模块：#24 一单一评、仅本人已归还租赁可评、评分摘要与分页。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ReviewIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolReviewMapper reviewMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();
    final List<Long> rentalIds = new ArrayList<>();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void cleanUp() {
        if (!rentalIds.isEmpty()) {
            reviewMapper.delete(new LambdaQueryWrapper<ToolReview>()
                    .in(ToolReview::getRentalId, rentalIds));
            for (Long id : rentalIds) {
                rentalMapper.deleteById(id);
            }
            rentalIds.clear();
        }
    }

    @Test
    void returned_rental_review_is_created_and_reflected_in_summaries() {
        String user = userLogin();
        long toolId = 1L;
        Tool tool = toolMapper.selectById(toolId);
        long rentalId = insertRental(1L, tool, "RETURNED");

        JsonNode created = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", rentalId,
                "rating", 5,
                "content", "  很好用<script>alert(1)</script>  ",
                "images", List.of("https://example.com/a.jpg", "https://example.com/b.jpg"))));

        JsonNode review = created.get("data");
        assertEquals(rentalId, review.get("rentalId").asLong(), review.toString());
        assertEquals(toolId, review.get("toolId").asLong(), review.toString());
        assertEquals("手电钻", review.get("toolName").asString(), review.toString());
        assertEquals(5, review.get("rating").asInt(), review.toString());
        assertEquals("很好用", review.get("content").asString(), review.toString());
        assertEquals(2, review.get("images").size(), review.toString());
        assertEquals("user_mjafo8", review.get("userNickname").asString(), review.toString());

        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        assertEquals(1, mine.get("data").get("total").asInt(), mine.toString());
        assertEquals(rentalId,
                mine.get("data").get("records").get(0).get("rentalId").asLong(), mine.toString());

        JsonNode toolReviews = ok(call(HttpMethod.GET,
                "/api/user/tools/" + toolId + "/reviews?page=1&size=10", user, null));
        assertEquals(1, toolReviews.get("data").get("total").asInt(), toolReviews.toString());
        assertEquals("很好用",
                toolReviews.get("data").get("records").get(0).get("content").asString(),
                toolReviews.toString());

        JsonNode detail = ok(call(HttpMethod.GET, "/api/user/tools/" + toolId, user, null));
        assertEquals(0, detail.get("data").get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("5.0")), detail.toString());
        assertEquals(1, detail.get("data").get("reviewCount").asInt(), detail.toString());

        JsonNode list = ok(call(HttpMethod.GET, "/api/user/tools?size=100", user, null));
        JsonNode listed = findById(list.get("data").get("records"), toolId);
        assertTrue(listed != null, "工具列表应包含被评价工具");
        assertEquals(0, listed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("5.0")), listed.toString());
        assertEquals(1, listed.get("reviewCount").asInt(), listed.toString());
    }

    @Test
    void duplicate_review_is_rejected() {
        String user = userLogin();
        Tool tool = toolMapper.selectById(1L);
        long rentalId = insertRental(1L, tool, "RETURNED");
        Map<String, Object> body = Map.of("rentalId", rentalId, "rating", 4, "content", "第一次评价");

        ok(call(HttpMethod.POST, "/api/user/reviews", user, body));
        ApiResponse duplicate = call(HttpMethod.POST, "/api/user/reviews", user, body);
        assertEquals(409, duplicate.status(), duplicate.body().toString());
        assertEquals("C409", duplicate.body().get("code").asString(), duplicate.body().toString());

        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        assertEquals(1, mine.get("data").get("total").asInt(), mine.toString());
    }

    @Test
    void only_owner_and_returned_rental_can_be_reviewed() {
        String user = userLogin();
        Tool tool = toolMapper.selectById(1L);

        long foreignRental = insertRental(2L, tool, "RETURNED");
        ApiResponse foreign = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", foreignRental, "rating", 5));
        assertEquals(403, foreign.status(), foreign.body().toString());
        assertEquals("C403", foreign.body().get("code").asString(), foreign.body().toString());

        long pickedUp = insertRental(1L, tool, "PICKED_UP");
        ApiResponse notReturned = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", pickedUp, "rating", 5));
        assertEquals(409, notReturned.status(), notReturned.body().toString());
        assertEquals("C409", notReturned.body().get("code").asString(),
                notReturned.body().toString());

        ApiResponse missing = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", 999999999L, "rating", 5));
        assertEquals(404, missing.status(), missing.body().toString());
    }

    @Test
    void rating_content_and_images_are_validated() {
        String user = userLogin();
        Tool tool = toolMapper.selectById(1L);
        long rentalId = insertRental(1L, tool, "RETURNED");

        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 0)));
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 6)));
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 5, "content", "好".repeat(501))));
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 5, "images", List.of(
                        "https://example.com/1.jpg", "https://example.com/2.jpg",
                        "https://example.com/3.jpg", "https://example.com/4.jpg"))));

        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        assertEquals(0, mine.get("data").get("total").asInt(), mine.toString());
    }

    @Test
    void rating_and_image_boundaries_are_accepted() {
        String user = userLogin();
        Tool tool = toolMapper.selectById(1L);
        long first = insertRental(1L, tool, "RETURNED");
        long second = insertRental(1L, tool, "RETURNED");

        JsonNode low = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", first,
                "rating", 1,
                "content", "好".repeat(500),
                "images", List.of("https://example.com/1.jpg",
                        "https://example.com/2.jpg", "https://example.com/3.jpg"))));
        assertEquals(1, low.get("data").get("rating").asInt(), low.toString());
        assertEquals(500, low.get("data").get("content").asString().length(), low.toString());
        assertEquals(3, low.get("data").get("images").size(), low.toString());

        JsonNode high = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", second, "rating", 5, "content", "五星好评")));
        assertEquals(5, high.get("data").get("rating").asInt(), high.toString());
    }

    @Test
    void user_and_tool_review_pages_are_paginated_and_owner_scoped() {
        String user = userLogin();
        Tool tool = toolMapper.selectById(1L);
        for (int i = 0; i < 3; i++) {
            ok(call(HttpMethod.POST, "/api/user/reviews", user,
                    Map.of("rentalId", insertRental(1L, tool, "RETURNED"), "rating", 5 - i,
                            "content", "第 " + i + " 条")));
        }
        insertReviewDirect(2L, tool, 3);

        JsonNode firstPage = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=2", user, null));
        assertEquals(3, firstPage.get("data").get("total").asInt(), firstPage.toString());
        assertEquals(2, firstPage.get("data").get("records").size(), firstPage.toString());
        assertEquals(1, firstPage.get("data").get("page").asLong(), firstPage.toString());
        for (JsonNode record : firstPage.get("data").get("records")) {
            assertEquals(1L, record.get("userId").asLong(), record.toString());
        }

        JsonNode secondPage = ok(call(HttpMethod.GET, "/api/user/reviews?page=2&size=2", user, null));
        assertEquals(1, secondPage.get("data").get("records").size(), secondPage.toString());

        JsonNode toolFirst = ok(call(HttpMethod.GET,
                "/api/user/tools/1/reviews?page=1&size=2", user, null));
        assertEquals(4, toolFirst.get("data").get("total").asInt(), toolFirst.toString());
        assertEquals(2, toolFirst.get("data").get("records").size(), toolFirst.toString());
        JsonNode toolSecond = ok(call(HttpMethod.GET,
                "/api/user/tools/1/reviews?page=2&size=2", user, null));
        assertEquals(2, toolSecond.get("data").get("records").size(), toolSecond.toString());
    }

    @Test
    void tool_list_rating_summary_is_batched_per_tool() {
        String user = userLogin();
        Tool first = toolMapper.selectById(1L);
        Tool second = toolMapper.selectById(2L);
        insertReviewDirect(1L, first, 5);
        insertReviewDirect(1L, first, 4);
        insertReviewDirect(1L, first, 3);
        insertReviewDirect(2L, second, 2);

        JsonNode list = ok(call(HttpMethod.GET, "/api/user/tools?size=100", user, null));
        JsonNode firstListed = findById(list.get("data").get("records"), first.getId());
        JsonNode secondListed = findById(list.get("data").get("records"), second.getId());
        JsonNode thirdListed = findById(list.get("data").get("records"), 3L);
        assertTrue(firstListed != null && secondListed != null && thirdListed != null,
                list.toString());

        assertEquals(0, firstListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("4.0")), firstListed.toString());
        assertEquals(3, firstListed.get("reviewCount").asInt(), firstListed.toString());
        assertEquals(0, secondListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("2.0")), secondListed.toString());
        assertEquals(1, secondListed.get("reviewCount").asInt(), secondListed.toString());
        assertEquals(0, thirdListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("0.0")), thirdListed.toString());
        assertEquals(0, thirdListed.get("reviewCount").asInt(), thirdListed.toString());
    }

    @Test
    void tool_list_can_sort_by_rating_and_keeps_unrated_tools() {
        String user = userLogin();
        Tool low = toolMapper.selectById(1L);
        Tool high = toolMapper.selectById(2L);
        Tool unrated = toolMapper.selectById(7L);
        insertReviewDirect(1L, low, 1);
        insertReviewDirect(1L, high, 5);

        JsonNode list = ok(call(HttpMethod.GET,
                "/api/user/tools?category=WX&orderBy=rating&size=100", user, null));
        List<Long> ids = new ArrayList<>();
        list.get("data").get("records").forEach(node -> ids.add(node.get("id").asLong()));

        assertTrue(ids.indexOf(high.getId()) < ids.indexOf(low.getId()),
                "评分高的工具应排在前面: " + list);
        assertTrue(ids.contains(unrated.getId()),
                "无评价工具不能因为按评分排序被丢弃: " + list);
    }

    @Test
    void admin_can_list_reviews_by_tool() {
        String user = userLogin();
        String admin = adminLogin();
        Tool tool = toolMapper.selectById(1L);
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", insertRental(1L, tool, "RETURNED"), "rating", 5,
                        "content", "管理端可见")));

        JsonNode list = ok(call(HttpMethod.GET,
                "/api/admin/reviews?toolId=1&page=1&size=10", admin, null));
        assertEquals(1, list.get("data").get("total").asInt(), list.toString());
        assertEquals("管理端可见",
                list.get("data").get("records").get(0).get("content").asString(), list.toString());

        JsonNode all = ok(call(HttpMethod.GET, "/api/admin/reviews?page=1&size=10", admin, null));
        assertTrue(all.get("data").get("total").asInt() >= 1, all.toString());
    }

    @Test
    void tool_reviews_require_login() {
        ApiResponse response = call(HttpMethod.GET, "/api/user/tools/1/reviews", null, null);
        assertEquals(401, response.status(), response.body().toString());
    }

    private void insertReviewDirect(long userId, Tool tool, int rating) {
        ToolReview review = new ToolReview();
        long fakeRentalId = 900_000_000L + SEQ.incrementAndGet();
        review.setRentalId(fakeRentalId);
        review.setToolId(tool.getId());
        review.setToolName(tool.getName());
        review.setUserId(userId);
        review.setUserNickname("user_" + userId);
        review.setRating(rating);
        review.setContent("直接插入的评价");
        reviewMapper.insert(review);
        rentalIds.add(fakeRentalId);
    }

    private void assertUnprocessable(ApiResponse response) {
        assertEquals(422, response.status(), response.body().toString());
        assertEquals("C400", response.body().get("code").asString(), response.body().toString());
    }

    private JsonNode findById(JsonNode records, long id) {
        for (JsonNode record : records) {
            if (record.get("id").asLong() == id) {
                return record;
            }
        }
        return null;
    }

    private long insertRental(long userId, Tool tool, String status) {
        Rental rental = new Rental();
        rental.setOrderNo("IT-REVIEW-" + SEQ.incrementAndGet());
        rental.setToolId(tool.getId());
        rental.setToolCode(tool.getCode());
        rental.setToolName(tool.getName());
        rental.setUserId(userId);
        rental.setRenterName("评价测试居民");
        rental.setRenterPhone("139000001" + String.format("%02d", SEQ.get() % 100));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        rental.setStartTime(now.minusDays(2));
        rental.setEndTime(now.minusDays(1));
        rental.setPickupPerson("评价测试居民");
        rental.setDepositAmount(new BigDecimal("100.00"));
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        rental.setStatus(status);
        rental.setActualReturnTime("RETURNED".equals(status) ? now.minusDays(1) : null);
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
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
