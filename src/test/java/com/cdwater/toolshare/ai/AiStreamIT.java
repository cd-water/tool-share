package com.cdwater.toolshare.ai;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式问答集成测试（依赖全套中间件 + DeepSeek key，设 AI_IT=true 启用）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "AI_IT", matches = "true")
class AiStreamIT {

    @LocalServerPort
    int port;

    @Test
    void stream_emitsSourcesDeltaDone() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;

        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/user/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"zhangsan\",\"password\":\"Aa123456\"}", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "登录应成功: " + login.body());
        String token = login.body().split("\"token\":\"")[1].split("\"")[0];

        HttpResponse<String> stream = http.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"question\":\"你好\"}", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, stream.statusCode(), "流式端点应 200: " + stream.body());
        String text = stream.body();
        assertTrue(text.contains("event:sources"), "首帧应为 sources: " + text);
        assertTrue(text.contains("event:delta"), "应有 delta 帧: " + text);
        assertTrue(text.contains("event:done"), "应有 done 帧: " + text);
    }
}
