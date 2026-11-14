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
import com.cdwater.toolshare.shared.enums.MessageType;
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
 * 事务提交后副作用集成测试。
 * <p>
 * 测试范围：消息发送、Redis 到期提醒登记与向量索引更新是否严格晚于事务提交。
 * 依赖环境：随机端口完整应用、测试数据库、真实 Redis 与内存对象存储；
 * ToolIndexService 使用 MockitoSpyBean 观察索引调用。
 * 关键不变量：事务回滚时不得发消息、不得写 Redis 提醒、不得更新向量索引；
 * 只有提交成功后副作用才执行，避免“数据库已回滚但外部已生效”的不一致。
 * 覆盖组件：AfterCommit 包装器、MessageService、RentalReminderService、
 * ToolIndexService 与 ToolService.transition。
 * 测试方式：用 TransactionTemplate 显式控制提交/回滚，
 * 再用 Mapper 与 Redis 客户端回查副作用是否真正发生。
 * 回查是必要的：只断言“没有抛异常”无法证明副作用被延迟。
 * 副作用类型：数据库消息、Redis 到期任务、向量索引三类；
 * 三者都不允许在回滚事务中留下痕迹。
 * 提交与回滚使用同一业务代码路径，差异只在事务结果，
 * 因此可以确认延迟逻辑由 AfterCommit 而非调用方保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AfterCommitIntegrationTest extends AbstractIntegrationTest {

    // 测试创建的工具名与消息标题统一使用该前缀，清理时按前缀匹配。
    // 前缀同时用于消息标题与工具名，覆盖两类清理条件。
    private static final String PREFIX = "IT-AFTER-";
    /** Redis 有序集合键：到期提醒任务。 */
    // 到期提醒存放在 ZSet 中，score 表示提醒触发时间。
    // ZSet 的成员是租赁 ID，score 是提醒触发时间戳。
    private static final String KEY_AT = "remind:at";
    /** Redis 哈希键：提醒提前分钟数。 */
    // 提前量存放在 Hash 中，与 ZSet 的成员一一对应。
    // Hash 的 field 是租赁 ID，value 是提前分钟数。
    private static final String KEY_LEAD = "remind:lead";
    /** 固定测试租赁 ID，便于精确清理 Redis 键。 */
    // 固定租赁 ID 让 Redis 键可预测，清理时无需扫描全库。
    // 固定 ID 避开种子数据，避免与真实订单冲突。
    private static final long RENTAL_ID = 9_100_001L;

    /** 被测的事务提交后回调包装器。 */
    @Autowired
    // 被测对象：所有外部副作用都应经它登记到事务同步回调。
    // 所有外部副作用都应通过它登记，测试直接调用 run。
    AfterCommit afterCommit;
    /** 事务管理器，用于构造提交与回滚事务。 */
    @Autowired
    // 用编程式事务显式控制提交与回滚。
    // TransactionTemplate 由该管理器创建，事务边界可控。
    PlatformTransactionManager transactionManager;
    /** 消息服务，用于验证提交后发送。 */
    @Autowired
    // 消息服务在提交后发送站内消息。
    // 通知用户是提交后副作用的典型代表。
    MessageService messageService;
    /** 消息 Mapper，用于统计副作用结果。 */
    @Autowired
    // 消息表用于统计提交/回滚后实际落库的条数。
    // selectCount 是验证副作用是否落库的最终手段。
    MessageMapper messageMapper;
    /** 提醒服务，用于验证 Redis 侧副作用。 */
    @Autowired
    // 提醒服务在提交后写入 Redis 到期任务。
    // 提醒写入 Redis，验证非数据库副作用同样被延迟。
    RentalReminderService reminderService;
    /** 真实 Redis 客户端，用于断言提醒是否登记。 */
    @Autowired
    // 真实 Redis 客户端，直接检查键是否存在。
    // 直接访问 Redis，绕过服务层检查真实键状态。
    StringRedisTemplate redis;
    /** 工具服务，用于触发状态迁移与索引同步。 */
    @Autowired
    // 工具服务负责状态迁移，迁移内部会触发索引同步。
    // 状态迁移内部调用索引服务，用于验证第三方同步。
    ToolService toolService;
    /** 工具 Mapper，用于准备与清理测试工具。 */
    @Autowired
    // 工具 Mapper 用于准备与清理测试工具。
    // 工具插入与删除都走 Mapper，避免接口副作用干扰。
    ToolMapper toolMapper;
    /** 工具日志 Mapper，清理工具前先删日志。 */
    @Autowired
    // 工具日志引用工具，清理顺序必须是先日志后工具。
    // 工具日志是外键子表，清理顺序必须先于工具。
    ToolLogMapper toolLogMapper;
    /** 向量索引服务用 SpyBean 替换，观察同步调用。 */
    @MockitoSpyBean
    // SpyBean 保留真实实现，同时记录索引调用次数与参数。
    // Spy 记录 upsert/remove 的调用次数与参数。
    ToolIndexService toolIndexService;

    /** 编程式事务模板，方便在用例中显式提交或回滚。 */
    // 每个用例前重建，绑定当前注入的事务管理器。
    // 每个用例可独立设置提交或回滚，互不影响。
    TransactionTemplate tx;

    /**
     * 每个用例前准备事务模板，清理固定租赁 ID 的 Redis 提醒，
     * 清空索引 Spy 调用，并为非 Web 线程补上 Sa-Token Mock 上下文。
     */
    @BeforeEach
    void setUp() {
        // 事务模板封装了 begin/commit/rollback，避免手写事务边界。
        // 模板默认传播行为为 REQUIRED，回调内加入当前事务。
        tx = new TransactionTemplate(transactionManager);
        // 清理固定租赁 ID 的 ZSet 成员，防止上次执行残留。
        redis.opsForZSet().remove(KEY_AT, String.valueOf(RENTAL_ID));
        // 同步清理提前量哈希，两个键必须成对出现。
        redis.opsForHash().delete(KEY_LEAD, String.valueOf(RENTAL_ID));
        // 清空 Spy 记录，后续 verify 只统计本用例调用。
        clearInvocations(toolIndexService);
        // 非 Web 线程直接调用 service 时，Sa-Token 需要 Mock 上下文才能判断登录态。
        // 非 web 线程直接调用 service 时，Sa-Token 需要上下文才能判断登录态
        // 非 Web 线程没有请求上下文，需要手工塞入三个 Mock 模型。
        // 这层 Mock 让 Sa-Token 能判断登录态而不真正读取请求。
        // Mock 模型只在当前线程有效，不会影响其他测试线程。
        SaTokenContextForThreadLocalStaff.setModelBox(
                mock(SaRequest.class), mock(SaResponse.class), mock(SaStorage.class));
    }

    /**
     * 用例结束后清除 Sa-Token 线程上下文、Redis 提醒、测试消息与工具日志/工具。
     * 必须清理线程上下文，避免污染同一测试线程上的后续用例。
     */
    @AfterEach
    void cleanUp() {
        // 必须清除 ThreadLocal 模型，避免污染同一测试线程上的后续用例。
        // 清理 ThreadLocal，防止登录态泄漏到下一个用例。
        SaTokenContextForThreadLocalStaff.clearModelBox();
        // 清理 ZSet 成员，保持 Redis 环境回到基线。
        redis.opsForZSet().remove(KEY_AT, String.valueOf(RENTAL_ID));
        // 清理 Hash 提前量，与 ZSet 成对删除。
        redis.opsForHash().delete(KEY_LEAD, String.valueOf(RENTAL_ID));
        // 按标题前缀删除本类创建的消息。
        // likeRight 生成 PREFIX%，只命中本类消息。
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, PREFIX));
        // 按工具名前缀枚举测试工具。
        // 每个工具先删日志，避免外键阻断。
        toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX)).forEach(tool -> {
            // 日志按 toolId 精确删除。
            // 日志按工具主键删除，覆盖该工具的全部审计记录。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 日志清理完再删工具本身。
            // 工具最后删除，此时已无子表引用。
            toolMapper.deleteById(tool.getId());
        });
    }

    /**
     * 场景：在一个标记回滚的事务中登记消息副作用；再在提交事务中登记另一条。
     * 前置条件：副作用通过 AfterCommit 包装，只在事务同步回调中执行。
     * 断言目标：回滚消息数量为 0；提交消息数量为 1，
     * 证明消息不会早于事务结果发送。
     * 契约补充：AfterCommit 只在事务真正提交后执行回调；
     * 回滚时回调被丢弃，外部系统看不到任何副作用。
     * 用例分别验证回滚与提交两条路径，避免只测 happy path。
     * 消息落库是最终证据：回调执行次数无法直接观察，
     * 但消息条数能准确反映回调是否运行。
     * 两条消息使用不同标题，互不干扰计数。
     */
    @Test
    void message_is_sent_only_after_commit() {
        // 回滚分支先执行，验证最严格的“不发生”条件。
        // 在一个显式事务中登记消息发送，然后标记回滚。
        tx.executeWithoutResult(status -> {
            // 回调体本身是正常的消息发送逻辑，不应被执行。
            // 消息标题带前缀，便于清理与按标题计数。
            // 回调登记后不会立刻执行，而是挂到事务同步器。
            afterCommit.run(() -> messageService.notifyUser(1L, MessageType.SYSTEM, PREFIX + "ROLLBACK",
                    "回滚时不应写入"));
            // setRollbackOnly 不会立即回滚，而是等回调结束时统一处理。
            // 设置回滚标记后，事务结束时不会提交。
            status.setRollbackOnly();
        });
        // 这一步在事务结束后执行，读的是已回滚后的数据库。
        // 回滚后消息条数必须为 0，证明回调未执行。
        // 计数为 0 是回调未执行的直接证据。
        assertEquals(0, countByTitle(PREFIX + "ROLLBACK"));

        // 对照事务使用同样的 AfterCommit 调用方式。
        // 第二个事务不设回滚标记，正常提交。
        // 提交后回调执行，消息应真实落库。
        // 提交事务的回调在 commit 完成后才运行。
        tx.executeWithoutResult(status -> afterCommit.run(() ->
                messageService.notifyUser(1L, MessageType.SYSTEM, PREFIX + "COMMIT", "提交后应写入")));
        // 标题与前一条不同，便于分别计数。
        // 提交路径的条数必须为 1，证明副作用已生效。
        // 恰好一条，排除重复执行或漏执行。
        assertEquals(1, countByTitle(PREFIX + "COMMIT"));
    }

    /**
     * 场景：在回滚事务中登记 Redis 到期提醒，再在提交事务中重新登记。
     * 前置条件：提醒同时写有序集合与提前量哈希。
     * 断言目标：回滚后两个 Redis 键都不存在；提交后有序集合有分数、
     * 哈希中保存提前量 30，证明提醒只在提交后可见。
     * 契约补充：提醒同时写 ZSet 与 Hash，二者必须在提交后才可见；
     * 回滚时两个键都不存在，不能只回滚其中一个。
     * 使用固定租赁 ID，键名可预测，便于精确断言与清理。
     * ZSet score 表示提醒时间，Hash 保存提前分钟数；
     * 只检查其中一个键可能漏掉部分写入，因此两者都断言。
     * 提交后重新登记，证明同一提醒可重复写入而不残留回滚数据。
     */
    @Test
    void reminder_is_scheduled_only_after_commit() {
        // 构造最小租赁实体，只填充提醒需要的 ID 与起止时间。
        // 提醒只读取 ID 与起止时间，其余字段无需填充。
        Rental rental = new Rental();
        // 固定主键与 Redis 键一致，避免查询时再转换。
        // 主键与 Redis 成员保持一致，断言时无需映射。
        rental.setId(RENTAL_ID);
        // 开始时间在一小时后，提醒尚未到期。
        // 开始时间尚未到达，提醒任务应处于待触发状态。
        rental.setStartTime(LocalDateTime.now().plusHours(1));
        // 结束时间在一天后，用于计算提前量。
        // 结束时间用于计算提前量，不影响本用例断言。
        rental.setEndTime(LocalDateTime.now().plusDays(1));

        // 事务内登记提醒，然后回滚。
        tx.executeWithoutResult(status -> {
            // 提前量同时决定 ZSet score 与 Hash value。
            // 提前 30 分钟提醒，写入 ZSet 与 Hash。
            afterCommit.run(() -> reminderService.schedule(rental, 30));
            // 回滚后两个键都不应存在。
            status.setRollbackOnly();
        });
        // ZSet 中不应有该成员，score 查询返回 null。
        // score 为 null 表示成员不存在，说明 ZSet 未被写入。
        assertNull(redis.opsForZSet().score(KEY_AT, String.valueOf(RENTAL_ID)));
        // Hash 中不应有提前量，get 返回 null。
        // Hash 同样为空，两个键必须一起回滚。
        assertNull(redis.opsForHash().get(KEY_LEAD, String.valueOf(RENTAL_ID)));

        // 同一提醒重复登记应覆盖而非累加。
        // 新事务正常提交，重新登记同一提醒。
        tx.executeWithoutResult(status -> afterCommit.run(() -> reminderService.schedule(rental, 30)));
        // score 的具体数值由提醒时间决定，此处只校验存在性。
        // 提交后 ZSet 成员存在，score 非空。
        // 提交后成员存在，score 由提醒时间计算。
        assertNotNull(redis.opsForZSet().score(KEY_AT, String.valueOf(RENTAL_ID)));
        // 提前量以字符串存储，避免 Redis 数值精度问题。
        // Hash 中保存提前量 30，字符串形式与写入一致。
        // 提前量以字符串保存，与写入参数一致。
        assertEquals("30", redis.opsForHash().get(KEY_LEAD, String.valueOf(RENTAL_ID)));
    }

    /**
     * 场景：在事务中迁移工具状态后抛异常回滚；再在正常事务中迁移。
     * 前置条件：ToolIndexService 使用 SpyBean 观察。
     * 断言目标：回滚时从未调用 upsert/remove，数据库状态仍为 AVAILABLE；
     * 提交后调用一次 upsert 且携带正确工具，
     * 避免索引记录了未落库的状态。
     * 契约补充：工具状态迁移会触发向量索引 upsert/remove；
     * 这些调用必须放在提交后，回滚时一次都不能发生。
     * SpyBean 保留真实实现，同时记录调用参数，便于断言工具 ID。
     * 回滚分支用 assertThrows 捕获业务异常，同时验证事务已回滚；
     * 提交分支用 verify 确认 upsert 恰好发生一次。
     * 索引调用与数据库状态必须同进同退，不能只改其中一侧。
     */
    @Test
    void tool_index_is_updated_only_after_commit() {
        // 手工构造工具实体，字段与表结构一一对应。
        // 直接构造实体，绕过创建接口的审核与文件上传。
        Tool tool = new Tool();
        // 编码只要求唯一，不参与业务断言。
        // 工具编码带纳秒后缀，避免唯一约束冲突。
        // 纳秒取模降低并发执行时的编码碰撞概率。
        tool.setCode("SQ-G-TT-" + System.nanoTime() % 1_000_000);
        // 名称同时是工具列表的展示字段。
        // 名称带前缀，供清理逻辑识别。
        // 名称参与清理条件，前缀必须保留。
        tool.setName(PREFIX + "index");
        // 型号可为空串，表示未填写。
        // 空型号满足非空约束，展示层可回退为默认文案。
        tool.setModel("");
        // 分类与种子工具保持一致。
        // 分类枚举与种子数据一致，避免审核规则差异。
        tool.setCategory("WX");
        // 成色 NEW，不影响状态迁移。
        // 成色只影响展示，不参与状态迁移。
        tool.setDamageLevel("NEW");
        // ToolStatus.code() 返回数据库存储的字符串值。
        // 初始状态 AVAILABLE，是迁移的起点。
        // 可借状态是迁移到维修中的合法起点。
        tool.setStatus(ToolStatus.AVAILABLE.code());
        // 押金 100 元，满足必填约束。
        // 押金由数据库小数类型保存，测试不校验金额规则。
        tool.setDeposit(new BigDecimal("100"));
        // 存放位置为空串，测试不关心。
        // 空存放位置允许存在，不影响取件流程。
        tool.setStorageArea("");
        // 主图与图片列表为空，避免对象存储依赖。
        // 空主图避免依赖对象存储服务。
        tool.setImageUrl("");
        // 图片列表使用空串而非 null，符合表字段约束。
        tool.setImageUrls("");
        // 供应商为空串，展示字段可缺省。
        // 供应商为可选展示字段。
        tool.setSupplier("");
        // 审核状态 1 表示已通过。
        // 1 表示审核通过，工具才可被迁移。
        tool.setAuditStatus(1);
        // 初始租借次数为 0，迁移不会改变该字段。
        tool.setRentalCount(0);
        // 插入后工具已处于可用状态，可直接迁移。
        // 直接插入，避免走创建接口的审核流程。
        // 插入后主键回填，迁移时按 ID 重新读取。
        toolMapper.insert(tool);
        // 插入工具本身不会触发索引同步。
        // 清空 Spy，确保后续 verify 只统计迁移产生的调用。
        clearInvocations(toolIndexService);

        // assertThrows 同时验证异常类型与回滚结果。
        // 在事务中迁移到维修中，然后抛异常触发回滚。
        // 抛出运行时异常，Spring 默认回滚。
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            // 状态迁移成功，但事务随后被异常标记回滚。
            toolService.transition(toolMapper.selectById(tool.getId()), ToolStatus.MAINTENANCE,
                    "TEST", "rollback");
            // 异常必须向外传播，证明事务确实失败。
            throw new IllegalStateException("rollback");
        }));
        // 回滚路径不应调用 upsert。
        // upsert 一次都不能发生，否则索引会记录已回滚的状态。
        verify(toolIndexService, never()).upsert(any());
        // 回滚路径也不应调用 remove。
        // remove 同样不应发生，覆盖状态删除路径。
        verify(toolIndexService, never()).remove(anyLong());
        // 回查使用新的数据库连接，读到的是最终状态。
        // 数据库状态仍为 AVAILABLE，说明迁移已回滚。
        // 回滚后状态未持久化，证明事务边界生效。
        assertEquals(ToolStatus.AVAILABLE.code(), toolMapper.selectById(tool.getId()).getStatus());

        // 第二次迁移与第一次使用相同参数，差异只在事务结果。
        // 正常事务再次迁移到维修中。
        // 这次事务提交，索引同步应被执行。
        tx.executeWithoutResult(status -> toolService.transition(toolMapper.selectById(tool.getId()),
                ToolStatus.MAINTENANCE, "TEST", "commit"));
        // verify 默认期望恰好一次调用。
        // 只调用一次 upsert，且参数是刚迁移的工具。
        // 参数非空且 ID 匹配，排除批量或错误对象的调用。
        // 提交后索引同步执行且只执行一次。
        verify(toolIndexService).upsert(argThat(value ->
                value != null && tool.getId().equals(value.getId())));
    }

    /** 辅助方法：按消息标题统计落库数量，用于验证副作用是否执行。 */
    private long countByTitle(String title) {
        // selectCount 只统计标题完全相同的消息。
        // 用 count 而不是列表，避免加载消息内容。
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                // 标题带前缀，计数范围被限制在本用例。
                // 标题精确匹配，同一前缀下的其他消息不会计入。
                .eq(Message::getTitle, title));
    }
}
