package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolWatchService;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 蹲守关系两侧集合原子写入与清理；通知失败时保留关系，成功后幂等清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolWatchConsistencyIntegrationTest extends AbstractIntegrationTest {

    private static final String PREFIX = "IT-WATCH-";
    private static final long USER_ID = 9_800_001L;

    @MockitoBean
    MessageService messageService;
    @Autowired
    ToolWatchService watchService;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    StringRedisTemplate redis;

    Tool tool;

    @BeforeEach
    void setUp() {
        tool = new Tool();
        tool.setCode("SQ-G-TW-" + System.nanoTime() % 1_000_000);
        tool.setName(PREFIX + "tool");
        tool.setModel("");
        tool.setCategory("WX");
        tool.setDamageLevel("NEW");
        tool.setStatus(ToolStatus.MAINTENANCE.code());
        tool.setDeposit(new BigDecimal("100"));
        tool.setStorageArea("");
        tool.setImageUrl("");
        tool.setImageUrls("");
        tool.setSupplier("");
        tool.setAuditStatus(1);
        tool.setRentalCount(0);
        toolMapper.insert(tool);
    }

    @AfterEach
    void cleanUp() {
        redis.delete("watch:tool:" + tool.getId());
        redis.delete("watch:user:" + USER_ID);
        toolMapper.deleteById(tool.getId());
    }

    @Test
    void watch_and_unwatch_keep_both_sets_in_sync() {
        String toolKey = "watch:tool:" + tool.getId();
        String userKey = "watch:user:" + USER_ID;

        watchService.watch(USER_ID, tool.getId());
        assertTrue(isMember(toolKey, String.valueOf(USER_ID)));
        assertTrue(isMember(userKey, String.valueOf(tool.getId())));

        watchService.unwatch(USER_ID, tool.getId());
        assertFalse(isMember(toolKey, String.valueOf(USER_ID)));
        assertFalse(isMember(userKey, String.valueOf(tool.getId())));
    }

    @Test
    void failed_notification_keeps_relation_and_success_cleans_both_sides() {
        String toolKey = "watch:tool:" + tool.getId();
        String userKey = "watch:user:" + USER_ID;
        LocalDateTime availableAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        watchService.watch(USER_ID, tool.getId());

        when(messageService.notifyUserOnce(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        assertTrue(isMember(toolKey, String.valueOf(USER_ID)), "通知失败时不能丢蹲守关系");
        assertTrue(isMember(userKey, String.valueOf(tool.getId())), "通知失败时不能丢蹲守关系");

        when(messageService.notifyUserOnce(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        assertFalse(isMember(toolKey, String.valueOf(USER_ID)));
        assertFalse(isMember(userKey, String.valueOf(tool.getId())));

        // 已清理后再次触发不应重复通知
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        verify(messageService, times(2)).notifyUserOnce(eq(USER_ID), eq("TOOL_AVAILABLE"),
                anyString(), anyString(), anyString());
    }

    private boolean isMember(String key, String member) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key, member));
    }
}
