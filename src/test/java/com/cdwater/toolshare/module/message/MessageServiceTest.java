package com.cdwater.toolshare.module.message;

import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.shared.enums.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息写入失败必须返回 false（提醒消费据此保留队列并重试），去重命中视为成功。
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    MessageMapper messageMapper;
    @Mock
    AdminMapper adminMapper;
    @Mock
    UserMapper userMapper;
    @Mock
    UserNotificationPrefMapper prefMapper;
    @Mock
    SmsSender smsSender;
    @Mock
    MessageVoMapper messageVoMapper;
    @Mock
    MessageConfigService configService;

    MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageMapper, adminMapper, userMapper,
                prefMapper, smsSender, messageVoMapper, configService);
    }

    @Test
    void insert_failure_returns_false() {
        when(messageMapper.insert(any(Message.class))).thenThrow(new IllegalStateException("数据库不可用"));

        assertFalse(messageService.notifyUser(1L, MessageType.SYSTEM, "标题", "内容"));

    }

    @Test
    void existing_biz_key_is_treated_as_success() {
        when(messageMapper.selectCount(any())).thenReturn(1L);

        assertTrue(messageService.notifyUserOnce(1L, MessageType.SYSTEM, "标题", "内容", "BIZ-KEY"));
        verify(messageMapper, never()).insert(any(Message.class));
    }
}
