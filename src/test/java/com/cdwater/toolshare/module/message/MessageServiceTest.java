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
 * 消息服务单元测试。
 * <p>
 * 测试范围：站内消息写入的失败语义与基于业务键的幂等去重。
 * 依赖环境：Mockito 模拟的 Mapper、短信发送器与消息配置服务，无真实数据库。
 * 关键不变量：数据库写入失败必须返回 false，提醒消费者保留队列并重试；
 * 业务键已存在时视为成功且不再插入，避免重复提醒。
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    /** 消息表 Mapper，负责插入与业务键去重查询。 */
    @Mock
    MessageMapper messageMapper;
    /** 管理员 Mapper，用于解析管理端接收人。 */
    @Mock
    AdminMapper adminMapper;
    /** 用户 Mapper，用于解析用户端接收人。 */
    @Mock
    UserMapper userMapper;
    /** 通知偏好 Mapper，决定是否走短信等外部通道。 */
    @Mock
    UserNotificationPrefMapper prefMapper;
    /** 短信发送器，单元测试中不产生真实短信。 */
    @Mock
    SmsSender smsSender;
    /** 消息视图 Mapper，用于返回前端展示对象。 */
    @Mock
    MessageVoMapper messageVoMapper;
    /** 消息配置服务，提供模板与通道开关。 */
    @Mock
    MessageConfigService configService;

    /** 被测服务，在每个用例前由 Mock 依赖重新构造。 */
    // 被测对象本身不持有可变状态，但因依赖全部是 Mock，
    // 每个用例前重建可确保调用记录与桩返回值互不串扰。
    // 这样单个用例的失败不会影响后续用例的判定。
    // 字段不加 @InjectMocks，是为了显式控制构造参数顺序。
    MessageService messageService;

    /**
     * 每个用例前用全部 Mock 依赖重建消息服务，保证用例之间互不污染。
     */
    @BeforeEach
    void setUp() {
        // 构造参数顺序与生产环境保持一致，避免测试通过但装配错误。
        // 服务只依赖接口，单元测试无需启动 Spring 容器。
        // Mock 对象由 MockitoExtension 在每个用例前自动初始化。
        // 每个用例前重建服务实例，确保 Mockito 的调用记录与桩状态互不影响。
        // 服务本身无状态，重建成本很低，换取用例之间的强隔离。
        // 重建后上一用例的 verify 记录不会污染当前用例。
        messageService = new MessageService(messageMapper, adminMapper, userMapper,
                prefMapper, smsSender, messageVoMapper, configService);
    }

    /**
     * 场景：消息 insert 抛出数据库不可用异常。
     * 前置条件：messageMapper.insert 被 mock 为抛出运行时异常。
     * 断言目标：notifyUser 返回 false 而不是向上抛异常，
     * 使提醒消费者能够保留队列并稍后重试。
     */
    @Test
    void insert_failure_returns_false() {
        // 模拟数据库不可用：insert 抛出运行时异常，而非返回 0。
        // 这代表连接中断、约束冲突等无法在当下解决的故障。
        // 服务层必须把异常收敛为布尔返回值，让上层决定是否重试。
        // 若直接向上抛异常，消息消费者可能丢失队列上下文。
        // 因此这里同时覆盖「不抛出」与「返回 false」两个语义。
        // 异常类型选用 IllegalStateException，贴近基础设施故障的表现。
        // 桩只匹配 Message 类型，避免误拦截其他 Mapper 调用。
        when(messageMapper.insert(any(Message.class))).thenThrow(new IllegalStateException("数据库不可用"));

        // notifyUser 的契约：发送成功返回 true，失败返回 false。
        // 返回 false 让调用方保留队列消息并稍后重试。
        // 断言不抛异常，保证失败被显式建模而不是打断消费循环。
        // 用 assertFalse 而非 try/catch，测试失败时能直接看到异常栈。
        // 该行为与站内消息最终一致性的设计目标一致。
        // 接收人 ID 与消息类型只是透传参数，不影响失败语义。
        assertFalse(messageService.notifyUser(1L, MessageType.SYSTEM, "标题", "内容"));

    }

    /**
     * 场景：相同业务键的消息已经存在。
     * 前置条件：selectCount 返回 1，表示去重命中。
     * 断言目标：notifyUserOnce 返回 true 且不再执行 insert，
     * 实现幂等提醒，避免重复通知用户。
     */
    @Test
    void existing_biz_key_is_treated_as_success() {
        // 业务键去重：selectCount 返回 1 表示同一提醒已写入过。
        // 业务键通常由业务类型 + 业务 ID 组成，保证跨重试稳定。
        // 这里不关心查询条件细节，只模拟「已存在」这一结果。
        // 去重查询必须先于 insert 执行，否则仍会重复写入。
        // mock 返回值使用 1L，与 MyBatis-Plus 的计数返回类型一致。
        // 使用 any() 匹配任意条件，避免测试耦合具体 Wrapper 结构。
        when(messageMapper.selectCount(any())).thenReturn(1L);

        // 已存在时视为成功：对调用方而言目标状态已达成。
        // 返回 true 可让上游安全地确认并删除队列消息。
        // 幂等语义避免网络重试造成重复提醒。
        // 断言重点是布尔结果，而非插入次数（插入次数由下一行验证）。
        // 这与「至少一次投递 + 幂等消费」的常见消息模型匹配。
        // 业务键在此用例中固定为 BIZ-KEY，模拟同一业务事件的重放。
        assertTrue(messageService.notifyUserOnce(1L, MessageType.SYSTEM, "标题", "内容", "BIZ-KEY"));
        // 验证去重命中后完全没有执行 insert。
        // never() 比断言返回值更能证明没有产生副作用。
        // 若实现先插入再判重，该断言会失败并暴露设计问题。
        // 该检查也防止未来重构把去重逻辑挪到插入之后。
        // 测试只观察 Mapper 交互，不依赖真实数据库。
        verify(messageMapper, never()).insert(any(Message.class));
    }
}
