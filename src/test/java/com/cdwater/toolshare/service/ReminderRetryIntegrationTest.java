// 提醒重试集成测试：验证 Redis 到期提醒在消息失败时的保留与成功后的出队。
package com.cdwater.toolshare.service;

// MyBatis-Plus 条件构造器：清理时按订单号精确定位测试租赁单。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// 集成测试基类：提供随机端口、数据库与 Redis 等共享测试环境。
import com.cdwater.toolshare.AbstractIntegrationTest;
// 测试存储配置：用内存对象存储替代真实 S3，避免外部依赖。
import com.cdwater.toolshare.TestStorageConfig;
// 消息服务：被测提醒流程会调用它发送通知，此处用 mock 控制返回值。
import com.cdwater.toolshare.module.message.MessageService;
// 租赁单实体：构造提醒扫描所需的最小数据。
import com.cdwater.toolshare.module.rental.Rental;
// 租赁单 Mapper：直接改库插入与删除测试数据。
import com.cdwater.toolshare.module.rental.RentalMapper;
// 提醒扫描服务：被测对象，支持手动触发而非等待定时任务。
import com.cdwater.toolshare.module.rental.RentalReminderService;
// 消息类型枚举：调用消息服务时用于标识提醒类别。
import com.cdwater.toolshare.shared.enums.MessageType;
// 用例结束后执行清理，避免残留数据影响其他测试。
import org.junit.jupiter.api.AfterEach;
// JUnit 测试注解：标记本方法为一个独立测试用例。
import org.junit.jupiter.api.Test;
// Spring 依赖注入注解：由容器注入 Mapper、Redis 与提醒服务。
import org.springframework.beans.factory.annotation.Autowired;
// 启动完整应用上下文：使用随机端口，贴近真实运行环境。
import org.springframework.boot.test.context.SpringBootTest;
// 导入测试配置类：把自定义 Bean 注册进测试上下文。
import org.springframework.context.annotation.Import;
// Redis 字符串模板：直接操作提醒队列的有序集合与提前量哈希。
import org.springframework.data.redis.core.StringRedisTemplate;
// Mockito Bean 替换：只替换消息服务，其余依赖保持真实。
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 金额类型：构造押金与费用字段，避免浮点误差。
import java.math.BigDecimal;
// 时间类型：构造已开始、尚未结束的租期，触发到期提醒。
import java.time.LocalDateTime;

// 静态断言：校验提醒成员是否仍存在于 Redis 队列。
import static org.junit.jupiter.api.Assertions.assertNotNull;
// 静态断言：校验提醒成功处理后是否已从队列移除。
import static org.junit.jupiter.api.Assertions.assertNull;
// 匹配任意消息接收用户 ID，简化失败/成功两轮桩设置。
import static org.mockito.ArgumentMatchers.any;
// 匹配任意租赁单 ID 参数。
import static org.mockito.ArgumentMatchers.anyLong;
// 匹配任意消息文本参数。
import static org.mockito.ArgumentMatchers.anyString;
// Mockito 桩方法：指定消息服务的返回值以模拟成功或失败。
import static org.mockito.Mockito.when;

/**
 * 到期提醒重试集成测试。
 * <p>
 * 测试范围：Redis 到期提醒队列的消费与失败重试语义。
 * 依赖环境：随机端口完整应用、测试数据库、真实 Redis；消息服务用 MockitoBean 替换。
 * 关键不变量：消息写入失败时提醒必须留在队列等待下一轮；
 * 只有写入成功后才从有序集合与提前量哈希中移除，保证提醒不丢也不重复。
 * <p>
 * 覆盖路径：先让消息服务返回失败、再返回成功，分别验证“保留”与“出队”。
 * 扫描时机：测试直接调用 scanDueReminders，避免依赖真实定时调度。
 * 清理策略：每个用例后按固定订单号删除 Redis 成员与租赁单。
 * 隔离性：仅替换消息服务，Redis、数据库与提醒扫描逻辑均为真实实现。
 * 失败语义：通知失败时提醒必须留在队列，下一轮继续重试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ReminderRetryIntegrationTest extends AbstractIntegrationTest {

    // 有序集合的成员是租赁单 ID，分数为到期时间戳，便于按时间扫描。
    // 键名与生产代码保持一致，测试直接布置和检查同一份队列。
    /** Redis 有序集合键：按到期时间排序的提醒任务。 */
    private static final String KEY_AT = "remind:at";
    // 哈希记录每个成员提前多少分钟提醒，决定何时进入到期集合。
    // 与有序集合配套使用，清理时必须同时删除两个键。
    /** Redis 哈希键：保存每个提醒的提前分钟数。 */
    private static final String KEY_LEAD = "remind:lead";
    // 固定单号便于 selectOne 精确定位，避免误删其他测试的租赁单。
    // 前缀 IT 表明是集成测试数据，便于排查残留。
    /** 本类测试租赁单的固定单号，用于精确定位与清理。 */
    private static final String ORDER_NO = "IT-RETRY-REMIND";

    // 只替换消息服务：提醒扫描、Redis 操作与数据库访问保持真实。
    // 这样能验证失败重试的完整链路，而不是测 mock 自身。
    /** 消息服务用 MockitoBean 替换，以便精确控制成功/失败时序。 */
    @MockitoBean
    MessageService messageService;
    // 提醒扫描服务：直接调用扫描方法，等价于定时任务触发的一轮消费。
    // 每轮扫描会读取到期集合、判断提前量并尝试发送通知。
    /** 被测提醒扫描服务，手动触发而不等待真实调度。 */
    @Autowired
    RentalReminderService reminderService;
    // 租赁单 Mapper：插入满足扫描条件的最小租赁单，结束后删除。
    // 直接操作数据库可避免走完整下单流程，聚焦提醒队列语义。
    /** 租赁单 Mapper，用于准备提醒所需的最小租赁数据。 */
    @Autowired
    RentalMapper rentalMapper;
    // 真实 Redis 客户端：用于布置提醒任务并断言有序集合状态。
    // 测试直接操作生产同款键结构，验证消费与清理逻辑。
    /** 真实 Redis 客户端，用于布置任务并断言队列状态。 */
    @Autowired
    StringRedisTemplate redis;

    /**
     * 用例结束后从 Redis 的有序集合与提前量哈希中移除提醒，
     * 并删除测试租赁单，避免下一轮扫描误消费残留数据。
     * <p>
     * 清理顺序：先按订单号查租赁单，再删 Redis 有序集合成员、提前量哈希，最后删租赁单。
     * 若用例中途失败，仍会执行本方法，避免残留提醒被下一轮扫描误消费。
     * 使用固定订单号而非全表清理，避免影响并发运行的其他测试。
     */
    // 每个用例结束后执行清理，确保 Redis 与数据库无残留。
    @AfterEach
    void cleanUp() {
        // 按固定订单号查询本用例插入的租赁单，未找到时无需清理。
        Rental rental = rentalMapper.selectOne(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getOrderNo, ORDER_NO));
        // 仅在租赁单存在时删除对应 Redis 成员，保持清理幂等。
        if (rental != null) {
            // 从到期有序集合移除成员，避免后续扫描再次命中。
            redis.opsForZSet().remove(KEY_AT, String.valueOf(rental.getId()));
            // 同步删除提前量哈希字段，防止残留配置指向已删订单。
            redis.opsForHash().delete(KEY_LEAD, String.valueOf(rental.getId()));
            // 最后删除租赁单本身，完成数据闭环清理。
            rentalMapper.deleteById(rental.getId());
        }
    }

    /**
     * 场景：到期提醒已进入 Redis 队列，第一轮消息写入失败，第二轮成功。
     * 前置条件：消息服务被 mock 为先返回 false 再返回 true；提醒时间已到期。
     * 断言目标：失败后提醒仍留在有序集合中等待重试；
     * 成功后才从队列移除，验证“至少一次”消费与失败保留语义。
     * <p>
     * 时序设计：第一轮桩返回 false，验证提醒不会被误删；第二轮返回 true，验证成功后出队。
     * 断言方式：直接读取 Redis 有序集合中该成员的 score，存在即仍在队列。
     * 重试语义：失败时保留提醒，下一轮扫描会再次尝试，保证“至少一次”通知。
     * 去重语义：成功出队后不会重复提醒，避免用户收到多条相同到期通知。
     */
    @Test
    void failed_message_keeps_reminder_queued_for_retry() {
        // 第一步：构造一条满足到期提醒条件的租赁单。
        // 使用独立实体对象，避免影响其他测试的数据。
        Rental rental = new Rental();
        // 构造一条已支付、已预订且临近结束的租赁单，使扫描器立即命中到期条件。
        // 填写订单号、工具与租户等标识字段。
        // 工具快照字段用于消息内容展示，不影响扫描判定。
        rental.setOrderNo(ORDER_NO);
        rental.setToolId(1L);
        // 工具编号与名称写入快照，便于提醒文案引用。
        rental.setToolCode("SQ-G-WX-0001");
        rental.setToolName("提醒重试工具");
        // 租户 ID 与姓名用于确定消息接收人。
        rental.setUserId(1L);
        rental.setRenterName("测试居民");
        // 联系电话用于短信或站内消息触达，不参与扫描过滤。
        rental.setRenterPhone("13800000001");
        // 租期从一小时前开始、两小时后结束，当前时刻已进入提醒窗口。
        rental.setStartTime(LocalDateTime.now().minusHours(1));
        // 结束时间晚于当前，因此属于“即将到期”而非“已逾期”。
        rental.setEndTime(LocalDateTime.now().plusHours(2));
        // 取件人与联系电话随订单保存，提醒内容会复用这些字段。
        rental.setPickupPerson("测试居民");
        // 押金金额设为 100 元，满足已预订订单的提醒前提。
        rental.setDepositAmount(new BigDecimal("100"));
        // 押金状态为 PAID，表示用户已完成支付。
        rental.setDepositStatus("PAID");
        // 订单状态为 RESERVED，扫描器只处理此类活动订单。
        rental.setStatus("RESERVED");
        // 费用字段初始化为 0，避免空值参与金额计算。
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        // 三个金额字段保持零值，提醒阶段不涉及结算。
        rental.setRefundAmount(BigDecimal.ZERO);
        // 写入数据库并取得自增主键，作为 Redis 队列成员。
        // 插入后主键由数据库生成，后续断言直接复用该 ID。
        rentalMapper.insert(rental);

        // Redis 有序集合成员统一用租赁单 ID 的字符串形式。
        String member = String.valueOf(rental.getId());
        // 将提醒放入到期集合，并记录 30 分钟提前量。
        // 以当前秒级时间戳作为分数放入到期集合，扫描器会立即命中。
        redis.opsForZSet().add(KEY_AT, member, System.currentTimeMillis() / 1000);
        // 提前量设为 30 分钟，模拟用户设置的到期前提醒。
        redis.opsForHash().put(KEY_LEAD, member, "30");

        // 第一轮：让消息服务返回 false，模拟下游通知发送失败。
        when(messageService.notifyUserOnce(anyLong(), any(MessageType.class), anyString(), anyString(), anyString()))
                .thenReturn(false);
        // 失败原因不落地到队列，队列只关心是否发送成功。
        // 第一轮：消息写入失败，提醒必须保留在队列中。
        // 手动触发一轮扫描，走真实的到期判断与消息调用逻辑。
        reminderService.scanDueReminders();
        // 断言失败后提醒仍保留在有序集合，等待下一轮重试。
        assertNotNull(redis.opsForZSet().score(KEY_AT, member), "写入失败时应保留在队列中");

        // 第二轮：把消息服务桩改为成功，模拟下游恢复。
        when(messageService.notifyUserOnce(anyLong(), any(MessageType.class), anyString(), anyString(), anyString()))
                .thenReturn(true);
        // 再次扫描，应消费同一条提醒并成功发送。
        reminderService.scanDueReminders();
        // 断言成功后成员已从有序集合移除，不再重复提醒。
        assertNull(redis.opsForZSet().score(KEY_AT, member), "写入成功后应出队");
    }
}
