package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.message.SmsSender;
import com.cdwater.toolshare.module.message.UserNotificationPref;
import com.cdwater.toolshare.module.message.UserNotificationPrefMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 短信发送失败时消息记录 FAILED，且不动站内消息渠道。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.message.sms-enabled=true")
@Import(TestStorageConfig.class)
class SmsFailureIntegrationTest extends AbstractIntegrationTest {

    private static final String TITLE_PREFIX = "IT-MSG-FAIL-";
    private static final String KEY = TITLE_PREFIX + "KEY";

    @MockitoBean
    SmsSender smsSender;

    @Autowired
    MessageService messageService;
    @Autowired
    MessageMapper messageMapper;
    @Autowired
    UserNotificationPrefMapper prefMapper;

    @BeforeEach
    void setUp() {
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        UserNotificationPref pref = new UserNotificationPref();
        pref.setUserId(1L);
        pref.setMessageType("RENT_DUE");
        pref.setChannel("SMS");
        pref.setEnabled(1);
        prefMapper.insert(pref);
        doThrow(new IllegalStateException("Mock 网关不可用"))
                .when(smsSender).send(anyString(), anyString());
    }

    @AfterEach
    void cleanUp() {
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    @Test
    void sms_failure_marks_message_failed() {
        messageService.notifyUserOnce(1L, "RENT_DUE", TITLE_PREFIX + "TITLE",
                "发送失败的内容", KEY);

        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, KEY));
        assertNotNull(message);
        assertEquals("SMS", message.getChannel());
        assertEquals("FAILED", message.getDeliveryStatus());
        assertNull(message.getSentTime());
    }
}
