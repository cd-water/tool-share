package com.cdwater.toolshare.service;

import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalReminderService;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolIndexService;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.tx.AfterCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 外部副作用只在事务提交后执行：回滚时不发消息、不登记提醒、不写索引。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AfterCommitIntegrationTest extends AbstractIntegrationTest {

    private static final String PREFIX = "IT-AFTER-";
    private static final String KEY_AT = "remind:at";
    private static final String KEY_LEAD = "remind:lead";
    private static final long RENTAL_ID = 9_100_001L;

    @Autowired
    AfterCommit afterCommit;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MessageService messageService;
    @Autowired
    MessageMapper messageMapper;
    @Autowired
    RentalReminderService reminderService;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ToolService toolService;
    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolLogMapper toolLogMapper;
    @MockitoSpyBean
    ToolIndexService toolIndexService;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        redis.opsForZSet().remove(KEY_AT, String.valueOf(RENTAL_ID));
        redis.opsForHash().delete(KEY_LEAD, String.valueOf(RENTAL_ID));
        clearInvocations(toolIndexService);
        // 非 web 线程直接调用 service 时，Sa-Token 需要上下文才能判断登录态
        SaTokenContextForThreadLocalStaff.setModelBox(
                mock(SaRequest.class), mock(SaResponse.class), mock(SaStorage.class));
    }

    @AfterEach
    void cleanUp() {
        SaTokenContextForThreadLocalStaff.clearModelBox();
        redis.opsForZSet().remove(KEY_AT, String.valueOf(RENTAL_ID));
        redis.opsForHash().delete(KEY_LEAD, String.valueOf(RENTAL_ID));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, PREFIX));
        toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX)).forEach(tool -> {
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            toolMapper.deleteById(tool.getId());
        });
    }

    @Test
    void message_is_sent_only_after_commit() {
        tx.executeWithoutResult(status -> {
            afterCommit.run(() -> messageService.notifyUser(1L, "SYSTEM", PREFIX + "ROLLBACK",
                    "回滚时不应写入"));
            status.setRollbackOnly();
        });
        assertEquals(0, countByTitle(PREFIX + "ROLLBACK"));

        tx.executeWithoutResult(status -> afterCommit.run(() ->
                messageService.notifyUser(1L, "SYSTEM", PREFIX + "COMMIT", "提交后应写入")));
        assertEquals(1, countByTitle(PREFIX + "COMMIT"));
    }

    @Test
    void reminder_is_scheduled_only_after_commit() {
        Rental rental = new Rental();
        rental.setId(RENTAL_ID);
        rental.setStartTime(LocalDateTime.now().plusHours(1));
        rental.setEndTime(LocalDateTime.now().plusDays(1));

        tx.executeWithoutResult(status -> {
            afterCommit.run(() -> reminderService.schedule(rental, 30));
            status.setRollbackOnly();
        });
        assertNull(redis.opsForZSet().score(KEY_AT, String.valueOf(RENTAL_ID)));
        assertNull(redis.opsForHash().get(KEY_LEAD, String.valueOf(RENTAL_ID)));

        tx.executeWithoutResult(status -> afterCommit.run(() -> reminderService.schedule(rental, 30)));
        assertNotNull(redis.opsForZSet().score(KEY_AT, String.valueOf(RENTAL_ID)));
        assertEquals("30", redis.opsForHash().get(KEY_LEAD, String.valueOf(RENTAL_ID)));
    }

    @Test
    void tool_index_is_updated_only_after_commit() {
        Tool tool = new Tool();
        tool.setCode("SQ-G-TT-" + System.nanoTime() % 1_000_000);
        tool.setName(PREFIX + "index");
        tool.setModel("");
        tool.setCategory("WX");
        tool.setDamageLevel("NEW");
        tool.setStatus(ToolStatus.AVAILABLE.code());
        tool.setDeposit(new BigDecimal("100"));
        tool.setStorageArea("");
        tool.setImageUrl("");
        tool.setImageUrls("");
        tool.setSupplier("");
        tool.setAuditStatus(1);
        tool.setRentalCount(0);
        toolMapper.insert(tool);
        clearInvocations(toolIndexService);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            toolService.transition(toolMapper.selectById(tool.getId()), ToolStatus.MAINTENANCE,
                    "TEST", "rollback");
            throw new IllegalStateException("rollback");
        }));
        verify(toolIndexService, never()).upsert(any());
        verify(toolIndexService, never()).remove(anyLong());
        assertEquals(ToolStatus.AVAILABLE.code(), toolMapper.selectById(tool.getId()).getStatus());

        tx.executeWithoutResult(status -> toolService.transition(toolMapper.selectById(tool.getId()),
                ToolStatus.MAINTENANCE, "TEST", "commit"));
        verify(toolIndexService).upsert(argThat(value ->
                value != null && tool.getId().equals(value.getId())));
    }

    private long countByTitle(String title) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getTitle, title));
    }
}
