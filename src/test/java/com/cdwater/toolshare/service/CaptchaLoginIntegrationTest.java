package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.entity.User;
import com.cdwater.toolshare.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：发送验证码 + 手机号验证码登录（静默注册）。
 * 前置：docker compose up -d（MySQL + Redis）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaptchaLoginIntegrationTest {

    private static final String NEW_PHONE = "13900001111";

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    UserMapper userMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        redis.delete("captcha:user:" + NEW_PHONE);
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, NEW_PHONE));
    }

    private JsonNode post(String url, Map<String, String> body) {
        String raw = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    /** 发送验证码并返回 6 位码 */
    private String sendCode(String phone) {
        JsonNode node = post("/api/user/send-code", Map.of("phone", phone));
        assertEquals("A200", node.get("code").asString());
        return node.get("data").asString();
    }

    @Test
    void send_code_returns_code_and_stores_in_redis() {
        String code = sendCode(NEW_PHONE);
        assertEquals(6, code.length());
        assertTrue(code.chars().allMatch(Character::isDigit));
        assertEquals(code, redis.opsForValue().get("captcha:user:" + NEW_PHONE));
    }

    @Test
    void send_code_invalid_phone_bad_request() {
        JsonNode node = post("/api/user/send-code", Map.of("phone", "12345"));
        assertEquals("C400", node.get("code").asString());
    }

    @Test
    void login_by_code_wrong_code_rejected() {
        sendCode(NEW_PHONE);
        JsonNode node = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", "000000"));
        assertEquals("C400", node.get("code").asString());
    }

    @Test
    void login_by_code_silent_register() {
        String code = sendCode(NEW_PHONE);
        JsonNode node = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        assertEquals("A200", node.get("code").asString());
        assertEquals("Authorization", node.get("data").get("tokenName").asString());
        assertFalse(node.get("data").get("tokenValue").asString().isEmpty());

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, NEW_PHONE));
        assertNotNull(user);
        assertNull(user.getPassword());
        assertEquals(1, user.getStatus());
        assertTrue(user.getNickname().matches("user_[0-9a-z]{6}"));
    }

    @Test
    void login_by_code_captcha_one_time() {
        String code = sendCode(NEW_PHONE);
        JsonNode first = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        assertEquals("A200", first.get("code").asString());

        // 同一验证码重放必须被拒绝（一次性）
        JsonNode second = post("/api/user/login/by-code",
                Map.of("phone", NEW_PHONE, "code", code));
        assertEquals("C400", second.get("code").asString());
    }

    @Test
    void login_by_code_captcha_atomic_consume() throws Exception {
        // 预置已注册用户，排除并发静默注册的干扰
        User existing = new User();
        existing.setPhone(NEW_PHONE);
        existing.setNickname("user_aaa000");
        existing.setAvatar("");
        existing.setStatus(1);
        userMapper.insert(existing);

        String code = sendCode(NEW_PHONE);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> post("/api/user/login/by-code",
                        Map.of("phone", NEW_PHONE, "code", code))));
            }
            List<String> codes = new ArrayList<>();
            for (Future<JsonNode> f : futures) {
                codes.add(f.get().get("code").asString());
            }
            long a200 = codes.stream().filter("A200"::equals).count();
            long c400 = codes.stream().filter("C400"::equals).count();
            long s500 = codes.stream().filter("S500"::equals).count();
            // 验证码必须原子消费：恰好一个并发请求成功，其余全部 C400，无 S500
            assertEquals(1, a200, "并发消费同一验证码应恰好成功一次，实际 A200=" + a200);
            assertEquals(threads - 1, c400, "其余请求应全部 C400，实际=" + c400);
            assertEquals(0, s500, "不应出现服务端错误，实际 S500=" + s500);
        } finally {
            pool.shutdownNow();
        }
    }
}
