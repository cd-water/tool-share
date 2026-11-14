package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.Message;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息渠道、送达状态与居民提醒偏好。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"toolshare.message.sms-enabled=true", "toolshare.auth.expose-captcha=true"})
@Import(TestStorageConfig.class)
class MessageChannelIntegrationTest extends AbstractIntegrationTest {

    private static final String TITLE_PREFIX = "IT-MSG-";

    @LocalServerPort
    int port;

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
    }

    @AfterEach
    void cleanUp() {
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .in(UserNotificationPref::getUserId, List.of(1L, 2L)));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    @Test
    void disabled_reminder_type_is_not_sent() {
        String user = userLogin("13800000001");
        setPref(user, MessageType.RENT_DUE, false, "SYSTEM");

        JsonNode prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        assertFalse(prefOf(prefs, "RENT_DUE").get("enabled").asBoolean());

        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "DISABLED",
                "不应送达的内容", TITLE_PREFIX + "DISABLED-KEY");

        assertEquals(0, countByBizKey(TITLE_PREFIX + "DISABLED-KEY"));
    }

    @Test
    void system_messages_cannot_be_disabled() {
        String user = userLogin("13800000001");

        ApiResponse rejected = call(HttpMethod.PUT, "/api/user/notification-prefs/SYSTEM", user,
                Map.of("enabled", false, "channel", "SYSTEM"));
        assertEquals(400, rejected.status(), rejected.body().toString());
        assertTrue(rejected.body().get("message").asString().contains("系统消息"),
                rejected.body().toString());

        messageService.notifyUserOnce(1L, MessageType.SYSTEM, TITLE_PREFIX + "SYSTEM",
                "系统消息始终送达", TITLE_PREFIX + "SYSTEM-KEY");
        assertEquals(1, countByBizKey(TITLE_PREFIX + "SYSTEM-KEY"));
    }

    @Test
    void sms_preference_uses_mock_and_records_sent_status() {
        String user = userLogin("13800000001");
        setPref(user, MessageType.RENT_DUE, true, "SMS");

        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SMS",
                "短信渠道提醒", TITLE_PREFIX + "SMS-KEY");

        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, TITLE_PREFIX + "SMS-KEY"));
        assertNotNull(message);
        assertEquals("SMS", message.getChannel());
        assertEquals("SENT", message.getDeliveryStatus());
        assertNotNull(message.getSentTime());
    }

    @Test
    void preferences_are_scoped_to_owner() {
        String user1 = userLogin("13800000001");
        String user2 = userLoginByCode("13800000002");
        setPref(user1, MessageType.RENT_DUE, false, "SYSTEM");

        JsonNode user2Prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user2, null)
                .body().get("data");
        assertTrue(prefOf(user2Prefs, "RENT_DUE").get("enabled").asBoolean());

        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "OWNER-1",
                "用户1已关闭", TITLE_PREFIX + "OWNER-1-KEY");
        messageService.notifyUserOnce(2L, MessageType.RENT_DUE, TITLE_PREFIX + "OWNER-2",
                "用户2仍开启", TITLE_PREFIX + "OWNER-2-KEY");

        assertEquals(0, countByBizKey(TITLE_PREFIX + "OWNER-1-KEY"));
        assertEquals(1, countByBizKey(TITLE_PREFIX + "OWNER-2-KEY"));
    }

    @Test
    void announcement_is_available_in_preferences() {
        String user = userLogin("13800000001");

        JsonNode prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        assertTrue(prefOf(prefs, "ANNOUNCEMENT").get("enabled").asBoolean());
        assertEquals("公告", prefOf(prefs, "ANNOUNCEMENT").get("typeName").asString());

        setPref(user, MessageType.ANNOUNCEMENT, false, "SYSTEM");
        JsonNode updated = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        assertFalse(prefOf(updated, "ANNOUNCEMENT").get("enabled").asBoolean());
    }

    @Test
    void preference_endpoints_require_login() {
        ApiResponse response = call(HttpMethod.GET, "/api/user/notification-prefs", null, null);
        assertEquals(401, response.status(), response.body().toString());
    }

    private void setPref(String token, MessageType type, boolean enabled, String channel) {
        ApiResponse response = call(HttpMethod.PUT, "/api/user/notification-prefs/" + type.code(), token,
                Map.of("enabled", enabled, "channel", channel));
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString());
    }

    private JsonNode prefOf(JsonNode prefs, String type) {
        for (JsonNode pref : prefs) {
            if (type.equals(pref.get("type").asString())) {
                return pref;
            }
        }
        throw new AssertionError("偏好列表缺少类型: " + type);
    }

    private long countByBizKey(String bizKey) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, bizKey));
    }

    private String userLogin(String phone) {
        ApiResponse response = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", phone, "password", "Aa123456"));
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    private String userLoginByCode(String phone) {
        ApiResponse sent = call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", phone));
        assertEquals("A200", sent.body().get("code").asString(), sent.body().toString());
        ApiResponse response = call(HttpMethod.POST, "/api/user/login/by-code", null,
                Map.of("phone", phone, "code", sent.body().get("data").asString()));
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
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
