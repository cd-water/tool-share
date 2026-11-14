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

/**
 * 验证码频控：手机号每小时上限与 IP 每小时上限。
 * 放宽每分钟上限，便于在单测中验证每小时维度。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "toolshare.auth.expose-captcha=true",
        "toolshare.auth.rate-limit.phone-per-minute=100",
        "toolshare.auth.rate-limit.phone-per-hour=5",
        "toolshare.auth.rate-limit.ip-per-hour=10"
})
@Import(TestStorageConfig.class)
class CaptchaRateLimitIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        Set<String> rateKeys = redis.keys("captcha:rl:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redis.delete(rateKeys);
        }
    }

    private JsonNode send(String phone) {
        String raw = client.post().uri("/api/user/send-code")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone))
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    @Test
    void phone_is_limited_to_five_per_hour() {
        String phone = "13900005555";
        for (int i = 0; i < 5; i++) {
            assertEquals("A200", send(phone).get("code").asString(), "第 " + (i + 1) + " 次应放行");
        }
        assertEquals("C429", send(phone).get("code").asString());
    }

    @Test
    void ip_is_limited_to_ten_per_hour() {
        for (int i = 0; i < 10; i++) {
            assertEquals("A200", send("1390000" + (6000 + i)).get("code").asString(),
                    "第 " + (i + 1) + " 次应放行");
        }
        assertEquals("C429", send("13900006666").get("code").asString());
    }
}
