package com.cdwater.toolshare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 集成测试：管理端三接口 + 双账号体系隔离。
 * 前置：docker compose up -d；seed 管理员 admin/Aa123456。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAuthIntegrationTest {

    @LocalServerPort
    int port;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private JsonNode post(String url, Map<String, String> body) {
        return post(url, body, null);
    }

    /** 带 Bearer token 的 POST（用于受拦截器保护的接口，如 logout） */
    private JsonNode post(String url, Map<String, String> body, String tokenValue) {
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (tokenValue != null) {
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        String raw = spec.body(body).retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    private JsonNode get(String url, String tokenValue) {
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
        if (tokenValue != null) {
            spec.header("Authorization", "Bearer " + tokenValue);
        }
        String raw = spec.retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    /** 管理员登录并返回 tokenValue */
    private String adminLogin() {
        JsonNode node = post("/api/admin/login",
                Map.of("username", "admin", "password", "Aa123456"));
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    @Test
    void admin_login_success() {
        String token = adminLogin();
        assertFalse(token.isEmpty());
    }

    @Test
    void admin_login_wrong_password() {
        JsonNode node = post("/api/admin/login",
                Map.of("username", "admin", "password", "wrong"));
        assertEquals("C400", node.get("code").asString());
        assertEquals("账号或密码错误", node.get("message").asString());
    }

    @Test
    void admin_login_unknown_user_same_error() {
        JsonNode node = post("/api/admin/login",
                Map.of("username", "nobody", "password", "Aa123456"));
        assertEquals("C400", node.get("code").asString());
        assertEquals("账号或密码错误", node.get("message").asString());
    }

    @Test
    void admin_info_with_token() {
        String token = adminLogin();
        JsonNode node = get("/api/admin/info", token);
        assertEquals("A200", node.get("code").asString());
        assertEquals("admin", node.get("data").get("username").asString());
    }

    @Test
    void admin_logout_invalidates_token() {
        String token = adminLogin();
        // logout 受拦截器保护，必须携带 token
        JsonNode logout = post("/api/admin/logout", Map.of(), token);
        assertEquals("A200", logout.get("code").asString());
        assertEquals("C401", get("/api/admin/info", token).get("code").asString());
    }

    @Test
    void user_token_cannot_access_admin_api() {
        // 用户端 token（seed 用户1）访问管理端接口 → 管理端体系未登录 → C401
        JsonNode userLogin = post("/api/user/login/by-pwd",
                Map.of("phone", "13800000001", "password", "Aa123456"));
        String userToken = userLogin.get("data").get("tokenValue").asString();
        assertEquals("C401", get("/api/admin/info", userToken).get("code").asString());
    }

    @Test
    void admin_token_cannot_access_user_api() {
        String adminToken = adminLogin();
        assertEquals("C401", get("/api/user/info", adminToken).get("code").asString());
    }
}
