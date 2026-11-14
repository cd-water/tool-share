package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageConfig;
import com.cdwater.toolshare.module.message.MessageConfigMapper;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.message.UserNotificationPref;
import com.cdwater.toolshare.module.message.UserNotificationPrefMapper;
import com.cdwater.toolshare.shared.enums.MessageType;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 管理端消息渠道配置：持久化到数据库，发送逻辑实时读取配置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class MessageConfigIntegrationTest extends AbstractIntegrationTest {

    private static final String TITLE_PREFIX = "IT-MSG-CONFIG-";

    @LocalServerPort
    int port;

    @Autowired
    MessageConfigMapper configMapper;
    @Autowired
    MessageService messageService;
    @Autowired
    MessageMapper messageMapper;
    @Autowired
    UserNotificationPrefMapper prefMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        configMapper.deleteById(1L);
    }

    @AfterEach
    void cleanUp() {
        configMapper.deleteById(1L);
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    @Test
    void config_is_persisted_and_used_by_sending() {
        String admin = adminLogin();

        JsonNode initial = ok(call(HttpMethod.GET, "/api/admin/message-config", admin, null))
                .get("data");
        assertEquals("SYSTEM", initial.get("defaultChannel").asString());
        assertEquals(false, initial.get("smsEnabled").asBoolean());

        JsonNode updated = ok(call(HttpMethod.PUT, "/api/admin/message-config", admin, Map.of(
                "defaultChannel", "SMS", "smsEnabled", true))).get("data");
        assertEquals("SMS", updated.get("defaultChannel").asString());
        assertEquals(true, updated.get("smsEnabled").asBoolean());

        MessageConfig stored = configMapper.selectById(1L);
        assertNotNull(stored, "配置应写入数据库");
        assertEquals("SMS", stored.getDefaultChannel());
        assertEquals(1, stored.getSmsEnabled());

        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SMS",
                "短信配置生效", TITLE_PREFIX + "SMS-KEY");
        Message sms = messageByKey(TITLE_PREFIX + "SMS-KEY");
        assertNotNull(sms);
        assertEquals("SMS", sms.getChannel());
        assertEquals("SENT", sms.getDeliveryStatus());

        ok(call(HttpMethod.PUT, "/api/admin/message-config", admin, Map.of(
                "defaultChannel", "SMS", "smsEnabled", false)));
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SYSTEM",
                "短信关闭后回退站内", TITLE_PREFIX + "SYSTEM-KEY");
        Message system = messageByKey(TITLE_PREFIX + "SYSTEM-KEY");
        assertNotNull(system);
        assertEquals("SYSTEM", system.getChannel());
        assertEquals("DELIVERED", system.getDeliveryStatus());
    }

    private Message messageByKey(String key) {
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, key));
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    private String adminLogin() {
        ApiResponse response = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", "Aa123456"));
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
