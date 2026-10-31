package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：密码登录、登出、用户信息。
 * 前置：docker compose up -d；seed 用户1 13800000001/Aa123456、用户2 13800000002 无密码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserAuthIntegrationTest {

    private static final String USER1_PHONE = "13800000001";
    private static final String PASSWORD = "Aa123456";

    @LocalServerPort
    int port;

    @Autowired
    UserMapper userMapper;

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

    /** 密码登录并返回 tokenValue */
    private String login(String phone, String password) {
        JsonNode node = post("/api/user/login/by-pwd",
                Map.of("phone", phone, "password", password));
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    @Test
    void login_by_pwd_seed_user1_success() {
        String token = login(USER1_PHONE, PASSWORD);
        assertFalse(token.isEmpty());
    }

    @Test
    void login_by_pwd_wrong_password() {
        JsonNode node = post("/api/user/login/by-pwd",
                Map.of("phone", USER1_PHONE, "password", "wrong"));
        assertEquals("C400", node.get("code").asString());
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    @Test
    void login_by_pwd_unknown_phone_same_error() {
        // 不存在的手机号与密码错误提示一致，不泄露注册信息
        JsonNode node = post("/api/user/login/by-pwd",
                Map.of("phone", "13800009999", "password", PASSWORD));
        assertEquals("C400", node.get("code").asString());
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    @Test
    void login_by_pwd_no_password_user_same_error() {
        // seed 用户2 静默注册无密码：与密码错误提示一致
        JsonNode node = post("/api/user/login/by-pwd",
                Map.of("phone", "13800000002", "password", PASSWORD));
        assertEquals("C400", node.get("code").asString());
        assertEquals("手机号或密码错误", node.get("message").asString());
    }

    @Test
    void info_with_token() {
        String token = login(USER1_PHONE, PASSWORD);
        JsonNode node = get("/api/user/info", token);
        assertEquals("A200", node.get("code").asString());
        assertEquals("138****0001", node.get("data").get("phone").asString());
        assertEquals("user_mjafo8", node.get("data").get("nickname").asString());
    }

    @Test
    void info_without_token_unauthorized() {
        JsonNode node = get("/api/user/info", null);
        assertEquals("C401", node.get("code").asString());
    }

    @Test
    void info_with_garbage_token_unauthorized() {
        JsonNode node = get("/api/user/info", "not-a-real-token");
        assertEquals("C401", node.get("code").asString());
    }

    /** 发送任意值的 Authorization 头（不自动加 Bearer 前缀） */
    private JsonNode getWithAuthorization(String url, String headerValue) {
        String raw = client.get().uri(url)
                .header("Authorization", headerValue)
                .retrieve().body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    @Test
    void info_with_prefixless_token_unauthorized() {
        // 无 Bearer 前缀的裸 token → C401 而非 500
        JsonNode node = getWithAuthorization("/api/user/info", "rawtoken");
        assertEquals("C401", node.get("code").asString());
    }

    @Test
    void info_with_empty_bearer_token_unauthorized() {
        // Bearer 后为空 → C401 而非 500
        JsonNode node = getWithAuthorization("/api/user/info", "Bearer ");
        assertEquals("C401", node.get("code").asString());
    }

    @Test
    void wrong_http_method_bad_request() {
        // GET 打 POST 接口 → C400（客户端用法错误），而非 S500
        JsonNode node = get("/api/user/login/by-code", null);
        assertEquals("C400", node.get("code").asString());
    }

    @Test
    void unknown_route_under_protected_prefix_not_found() {
        // 受保护前缀下的未知路由 → C404（路由不存在），而非 C401
        JsonNode node = get("/api/user/nonexistent", null);
        assertEquals("C404", node.get("code").asString());
    }

    @Test
    void login_by_pwd_disabled_user_forbidden() {
        User u = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
        u.setStatus(0);
        userMapper.updateById(u);
        try {
            JsonNode node = post("/api/user/login/by-pwd",
                    Map.of("phone", USER1_PHONE, "password", PASSWORD));
            assertEquals("C403", node.get("code").asString());
        } finally {
            User u2 = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            u2.setStatus(1);
            userMapper.updateById(u2);
        }
    }

    @Test
    void logout_invalidates_token() {
        String token = login(USER1_PHONE, PASSWORD);
        // logout 受拦截器保护，必须携带 token
        JsonNode logout = post("/api/user/logout", Map.of(), token);
        assertEquals("A200", logout.get("code").asString());
        JsonNode node = get("/api/user/info", token);
        assertEquals("C401", node.get("code").asString());
    }

    @Test
    void concurrent_login_creates_distinct_valid_tokens() {
        // is-concurrent=true + is-share=false：每次登录新 token，旧 token 仍有效
        String t1 = login(USER1_PHONE, PASSWORD);
        String t2 = login(USER1_PHONE, PASSWORD);
        assertNotEquals(t1, t2);
        assertEquals("A200", get("/api/user/info", t1).get("code").asString());
        assertEquals("A200", get("/api/user/info", t2).get("code").asString());
    }

    @Test
    void info_disabled_user_forbidden() {
        String token = login(USER1_PHONE, PASSWORD);
        try {
            User u = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            u.setStatus(0);
            userMapper.updateById(u);

            JsonNode node = get("/api/user/info", token);
            assertEquals("C403", node.get("code").asString());
        } finally {
            User u = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE));
            u.setStatus(1);
            userMapper.updateById(u);
            assertTrue(userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getPhone, USER1_PHONE)).getStatus() == 1);
        }
    }
}
