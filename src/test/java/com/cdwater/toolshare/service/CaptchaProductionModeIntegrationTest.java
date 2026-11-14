package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭暴露开关：接口 data 返回 null，验证码仅存在于服务端，仍可用服务端存储的验证码登录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.auth.expose-captcha=false")
@Import(TestStorageConfig.class)
class CaptchaProductionModeIntegrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "13900004444";

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        redis.delete("captcha:user:" + PHONE);
        redis.delete("captcha:attempt:user:" + PHONE);
        Set<String> rateKeys = redis.keys("captcha:rl:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redis.delete(rateKeys);
        }
    }

    @Test
    void send_code_hides_code_in_production_mode() {
        String raw = client.post().uri("/api/user/send-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", PHONE))
                .retrieve()
                .body(String.class);
        JsonNode node = om.readTree(raw);

        assertEquals("A200", node.get("code").asString());
        assertTrue(node.get("data").isNull(), "关闭开关时 data 应为 null");

        String stored = redis.opsForValue().get("captcha:user:" + PHONE);
        assertTrue(stored != null && stored.matches("\\d{6}"), "验证码应仍写入服务端");

        String loginRaw = client.post().uri("/api/user/login/by-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", PHONE, "code", stored))
                .retrieve()
                .body(String.class);
        JsonNode login = om.readTree(loginRaw);
        assertEquals("A200", login.get("code").asString());
        assertFalse(login.get("data").get("tokenValue").asString().isEmpty());
    }
}
