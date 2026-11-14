package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.rental.RentalReminderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 到期提醒消费：消息写入失败时保留在 Redis 队列，下一轮重试成功后才出队。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ReminderRetryIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY_AT = "remind:at";
    private static final String KEY_LEAD = "remind:lead";
    private static final String ORDER_NO = "IT-RETRY-REMIND";

    @MockitoBean
    MessageService messageService;
    @Autowired
    RentalReminderService reminderService;
    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        Rental rental = rentalMapper.selectOne(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getOrderNo, ORDER_NO));
        if (rental != null) {
            redis.opsForZSet().remove(KEY_AT, String.valueOf(rental.getId()));
            redis.opsForHash().delete(KEY_LEAD, String.valueOf(rental.getId()));
            rentalMapper.deleteById(rental.getId());
        }
    }

    @Test
    void failed_message_keeps_reminder_queued_for_retry() {
        Rental rental = new Rental();
        rental.setOrderNo(ORDER_NO);
        rental.setToolId(1L);
        rental.setToolCode("SQ-G-WX-0001");
        rental.setToolName("提醒重试工具");
        rental.setUserId(1L);
        rental.setRenterName("测试居民");
        rental.setRenterPhone("13800000001");
        rental.setStartTime(LocalDateTime.now().minusHours(1));
        rental.setEndTime(LocalDateTime.now().plusHours(2));
        rental.setPickupPerson("测试居民");
        rental.setDepositAmount(new BigDecimal("100"));
        rental.setDepositStatus("PAID");
        rental.setStatus("RESERVED");
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        rentalMapper.insert(rental);

        String member = String.valueOf(rental.getId());
        redis.opsForZSet().add(KEY_AT, member, System.currentTimeMillis() / 1000);
        redis.opsForHash().put(KEY_LEAD, member, "30");

        when(messageService.notifyUserOnce(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        reminderService.scanDueReminders();
        assertNotNull(redis.opsForZSet().score(KEY_AT, member), "写入失败时应保留在队列中");

        when(messageService.notifyUserOnce(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        reminderService.scanDueReminders();
        assertNull(redis.opsForZSet().score(KEY_AT, member), "写入成功后应出队");
    }
}
