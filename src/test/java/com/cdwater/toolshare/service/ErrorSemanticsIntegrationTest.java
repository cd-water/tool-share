package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
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

/**
 * 集成测试：语义化 HTTP 状态码与统一 envelope 共存。
 * 覆盖 401 / 403 / 404 / 409 / 422，成功响应仍为 200 + A200。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ErrorSemanticsIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-错误语义-";
    private static final String USER1_PHONE = "13800000001";
    private static final String PASSWORD = "Aa123456";

    @LocalServerPort
    int port;

    @Autowired
    UserMapper userMapper;

    @Autowired
    ToolMapper toolMapper;

    @Autowired
    RentalMapper rentalMapper;

    @Autowired
    ToolLogMapper toolLogMapper;

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
            rentalMapper.delete(new LambdaQueryWrapper<Rental>().eq(Rental::getToolId, tool.getId()));
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, tool.getId()));
            toolMapper.deleteById(tool.getId());
        }
    }

    @Test
    void success_still_returns_200_with_a200() {
        ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", USER1_PHONE, "password", PASSWORD));
        assertEquals(200, resp.status());
        assertEquals("A200", resp.body().get("code").asString());
    }

    @Test
    void unauthorized_returns_401_with_c401() {
        ApiResponse resp = call(HttpMethod.GET, "/api/user/info", null, null);
        assertEquals(401, resp.status());
        assertEquals("C401", resp.body().get("code").asString());
    }

    @Test
    void forbidden_returns_403_with_c403() {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
        user.setStatus(0);
        userMapper.updateById(user);
        try {
            ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                    Map.of("phone", USER1_PHONE, "password", PASSWORD));
            assertEquals(403, resp.status());
            assertEquals("C403", resp.body().get("code").asString());
        } finally {
            User restored = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            restored.setStatus(1);
            userMapper.updateById(restored);
        }
    }

    @Test
    void unknown_route_returns_404_with_c404() {
        ApiResponse resp = call(HttpMethod.GET, "/api/user/nonexistent", null, null);
        assertEquals(404, resp.status());
        assertEquals("C404", resp.body().get("code").asString());
    }

    @Test
    void booking_conflict_returns_409_with_c409() {
        String adminToken = adminLogin();
        String userToken = userLogin();
        long toolId = createTool(adminToken);
        String start = LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT);
        Map<String, Object> booking = Map.of(
                "toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国");

        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals", userToken, booking).status());
        ApiResponse conflict = call(HttpMethod.POST, "/api/user/rentals", userToken, booking);
        assertEquals(409, conflict.status());
        assertEquals("C409", conflict.body().get("code").asString());
    }

    @Test
    void validation_failure_returns_422_with_c400() {
        ApiResponse resp = call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", "12345"));
        assertEquals(422, resp.status());
        assertEquals("C400", resp.body().get("code").asString());
    }

    @Test
    void oversized_field_returns_422() {
        String adminToken = adminLogin();
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", "超".repeat(101),
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100));
        assertEquals(422, resp.status());
        assertEquals("C400", resp.body().get("code").asString());
    }

    @Test
    void negative_amount_returns_422() {
        String adminToken = adminLogin();
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", PREFIX + "负金额",
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", -1));
        assertEquals(422, resp.status());
        assertEquals("C400", resp.body().get("code").asString());
    }

    @Test
    void invalid_id_card_returns_422() {
        String userToken = userLogin();
        ApiResponse resp = call(HttpMethod.PUT, "/api/user/profile", userToken,
                Map.of("idCard", "310101199001011235"));
        assertEquals(422, resp.status());
        assertEquals("C400", resp.body().get("code").asString());
    }

    @Test
    void wrong_method_returns_400_with_c400() {
        ApiResponse resp = call(HttpMethod.GET, "/api/user/login/by-code", null, null);
        assertEquals(400, resp.status());
        assertEquals("C400", resp.body().get("code").asString());
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
                return new ApiResponse(
                        response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long createTool(String adminToken) {
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/tools", adminToken, Map.of(
                "name", PREFIX + "工具",
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "测试说明",
                "imageUrls", List.of()));
        assertEquals(200, resp.status(), resp.body().toString());
        long id = resp.body().get("data").get("id").asLong();
        ApiResponse audit = call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit",
                adminToken, Map.of("auditStatus", "APPROVED"));
        assertEquals(200, audit.status(), audit.body().toString());
        return id;
    }

    private String userLogin() {
        ApiResponse resp = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", USER1_PHONE, "password", PASSWORD));
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }

    private String adminLogin() {
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", PASSWORD));
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }
}
