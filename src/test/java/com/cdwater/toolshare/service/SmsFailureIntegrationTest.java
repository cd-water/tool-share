// 短信通道失败集成测试：验证网关异常时消息仍被可靠落库。
// 推送偏好选择短信后，发送失败不能被吞掉，也不能误记为成功。
// 用例使用 MockitoBean 替换 SmsSender，让每次发送都抛异常。
// 同时通过配置打开全局短信开关，确保代码走真实短信分支。
// 断言重点是渠道、投递状态与发送时间三个字段。
// 这些字段决定补偿任务能否识别并重试失败消息。
// 依赖完整 Spring 上下文、测试数据库与内存对象存储。
// 所有注释仅说明测试意图，不改变断言与执行顺序。
package com.cdwater.toolshare.service;

// MyBatis-Plus 条件构造器用于清理和查询测试消息与偏好。
// 集成测试基类负责数据库、迁移与对象存储的初始化。
// 测试存储配置使用内存实现，避免依赖外部服务。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
// 消息实体、Mapper 与服务是本次被测链路的三个入口。
// 短信发送器接口被 mock，用来稳定复现网关故障。
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.message.SmsSender;
import com.cdwater.toolshare.module.message.UserNotificationPref;
import com.cdwater.toolshare.module.message.UserNotificationPrefMapper;
import com.cdwater.toolshare.shared.enums.MessageType;
// JUnit 生命周期注解负责准备偏好、制造故障与清理数据。
// Spring 测试注解启动完整上下文并提供依赖注入。
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// @Autowired 注入真实服务与 Mapper，保持与生产一致的调用路径。
// MockitoBean 覆盖短信发送器，使其只在本测试上下文中失效。
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 断言消息落库后的渠道、状态与发送时间。
// 时间字段必须为空，才能让补偿任务识别未发送成功的记录。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
// anyString 匹配任意短信参数，doThrow 让网关调用稳定抛错。
// 故障注入只影响短信发送，不改变站内信与数据库写入逻辑。
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 短信通道失败集成测试。
 * <p>
 * 测试范围：用户偏好选择短信且网关异常时的消息落库状态。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储；
 * 短信发送器用 MockitoBean 模拟故障，全局配置开启短信。
 * 关键不变量：发送失败的消息渠道仍为 SMS、投递状态为 FAILED；
 * 发送时间保持为空，便于后续补偿任务识别与重试。
 * 补充说明：消息对象由 MessageService 通过业务键去重后写入。
 * 短信发送失败发生在消息持久化之后，因此失败状态必须可查询。
 * 渠道保持 SMS 便于运维按渠道统计失败量。
 * 投递状态 FAILED 与空发送时间共同构成补偿任务的筛选条件。
 * 测试不模拟网关恢复，只验证失败分支的状态落库。
 * 用例结束后按标题前缀清理，避免污染其它消息测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.message.sms-enabled=true")
@Import(TestStorageConfig.class)
class SmsFailureIntegrationTest extends AbstractIntegrationTest {

    // 标题前缀用于精确清理本类产生的消息记录。
    // 前缀同时出现在标题与业务键中，便于按类隔离测试数据。
    // 清理使用 likeRight 匹配，只删除本类消息。
    private static final String TITLE_PREFIX = "IT-MSG-FAIL-";
    // 业务键在服务端用于幂等去重，同一键只应落一条消息。
    // 固定业务键让测试可以精确定位本次调用产生的记录。
    /** 本用例消息的固定业务键，用于精确定位落库记录。 */
    private static final String KEY = TITLE_PREFIX + "KEY";

    // 用 MockitoBean 替换真实短信发送器，避免测试触达外部网关。
    // 替换作用域仅限当前测试类，不影响其它集成测试。
    // 失败行为在 setUp 中通过 doThrow 注入。
    /** 短信发送器用 MockitoBean 替换，模拟网关不可用。 */
    @MockitoBean
    SmsSender smsSender;

    // 被测消息服务负责偏好判断、去重与落库。
    // 通过它调用真实业务入口，而不是直接操作 Mapper。
    /** 被测消息服务。 */
    @Autowired
    MessageService messageService;
    // 消息 Mapper 用于按业务键查询最终落库结果。
    // 查询结果直接反映服务在失败路径上的持久化行为。
    /** 消息 Mapper，用于核对失败后的投递状态。 */
    @Autowired
    MessageMapper messageMapper;
    // 偏好 Mapper 用于准备短信渠道偏好并做清理。
    // 偏好决定消息服务选择短信还是站内信通道。
    /** 用户通知偏好 Mapper，用于准备短信渠道偏好。 */
    @Autowired
    UserNotificationPrefMapper prefMapper;

    /**
     * 每个用例前重置用户通知偏好为短信渠道，并让短信网关抛出异常。
     * 这样发送路径必然进入失败分支，同时不影响站内信逻辑。
     * 偏好写入前先删除同一用户的旧记录，避免唯一键冲突。
     * 短信故障通过 Mockito 的 doThrow 注入到任意参数调用。
     * 这样消息服务进入短信发送分支后必然收到异常。
     * 站内信与数据库写入不受影响，故障范围被限制在短信通道。
     */
    @BeforeEach
    void setUp() {
        // 先删除用户 1 的历史偏好，保证本次插入是唯一有效配置。
        // 偏好表按用户与消息类型区分渠道，残留记录会干扰选择结果。
        // 清理只针对用户 1，避免影响其它测试准备的偏好数据。
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        // 构造短信渠道的通知偏好，模拟用户主动选择短信提醒。
        // 消息类型 RENT_DUE 对应租赁到期提醒。
        // 只有类型与渠道同时匹配，消息服务才会走短信发送。
        UserNotificationPref pref = new UserNotificationPref();
        // 绑定种子用户 1，与被测消息的接收人保持一致。
        // 用户 ID 决定偏好查询与消息归属。
        pref.setUserId(1L);
        // 指定消息类型为租赁到期提醒，与测试消息类型一致。
        // 类型不一致时偏好不会生效，短信故障分支也就不会触发。
        pref.setMessageType("RENT_DUE");
        // 渠道设为 SMS，强制消息服务选择短信发送。
        // 这是本次测试要覆盖的故障分支入口。
        pref.setChannel("SMS");
        // enabled=1 表示该偏好启用。
        // 关闭状态的偏好会被忽略，无法走到发送逻辑。
        pref.setEnabled(1);
        // 写入偏好后，消息服务即可在通知时读取到短信配置。
        // 插入使用真实 Mapper，确保数据库约束与生产一致。
        prefMapper.insert(pref);
        // 让短信发送器在任意手机号与内容下都抛出运行时异常。
        // 这里选择 IllegalStateException 模拟网关不可用的常见故障。
        // Mock 只替换发送动作，不改变消息服务的异常处理逻辑。
        // 异常必须被消息服务捕获并转换为 FAILED 状态，而不是向外抛出。
        doThrow(new IllegalStateException("Mock 网关不可用"))
                .when(smsSender).send(anyString(), anyString());
    }

    /**
     * 用例结束后删除用户偏好与本类产生的消息，避免污染其他测试。
     * 删除偏好可以避免短信配置泄漏到后续测试。
     * 消息按标题前缀清理，只移除本类产生的记录。
     * 清理顺序为先偏好后消息，彼此没有外键依赖。
     */
    @AfterEach
    void cleanUp() {
        // 删除用户 1 的短信偏好，恢复测试前的默认状态。
        // 若不清理，下个用例可能误用短信通道而改变断言结果。
        // 条件按用户 ID 精确匹配，避免误删其它用户偏好。
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        // 按标题前缀删除本类消息，保留其它测试的消息数据。
        // 前缀与 KEY 共用，能覆盖本用例写入的全部记录。
        // 删除操作在用例结束后执行，保证失败时也尽量清理。
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                // likeRight 生成前缀匹配条件，只命中以固定前缀开头的标题。
                // 这样可以避免全表删除造成测试间相互影响。
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    /**
     * 场景：用户偏好短信，发送时短信网关不可用。
     * 前置条件：SmsSender 被 mock 为抛异常，全局短信开关打开。
     * 断言目标：消息仍落库，渠道为 SMS、投递状态为 FAILED、发送时间为空，
     * 补充说明：通知调用使用 notifyUserOnce，带业务键做幂等。
     * 偏好为短信且网关抛错时，服务应捕获异常并继续落库。
     * 渠道字段必须保留 SMS，便于区分失败来源。
     * 投递状态写 FAILED，表示本次渠道投递未成功。
     * sentTime 保持为空，补偿任务据此判定消息尚未送达。
     * 三个字段共同构成可重试的失败消息记录。
     * 便于后续补偿或人工排查，同时不误记为成功。
     */
    @Test
    void sms_failure_marks_message_failed() {
        // 调用消息服务发送一条租赁到期提醒。
        // 业务键 KEY 用于幂等，确保查询时只命中这一条记录。
        // 用户 1 的偏好为短信，因此服务内部会调用短信发送器。
        // 短信发送器已被配置为抛异常，调用将进入失败分支。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "TITLE",
                "发送失败的内容", KEY);

        // 按业务键查询消息，验证失败后记录是否仍被持久化。
        // 使用 selectOne 是因为业务键在服务层用于幂等去重。
        // 若消息未落库，后续断言会直接失败并暴露异常吞没问题。
        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                // 条件精确匹配业务键，避免读到其它测试的消息。
                // 查询只关心失败路径是否留下可追溯的记录。
                .eq(Message::getBizKey, KEY));
        // 消息必须存在，证明短信失败没有阻止持久化。
        // 如果服务在异常时回滚或跳过写入，这条断言会失败。
        // 可靠落库是后续补偿与人工排查的前提。
        assertNotNull(message);
        // 渠道仍为 SMS，说明消息走的是用户选择的短信通道。
        // 即使发送失败也不应改成站内信，否则会掩盖渠道故障。
        // 该字段同时用于运维按渠道统计失败率。
        assertEquals("SMS", message.getChannel());
        // 投递状态为 FAILED，表示短信网关调用未成功。
        // 服务必须捕获异常并显式标记失败，不能误记为成功。
        // 补偿任务依赖该状态筛选待重试消息。
        assertEquals("FAILED", message.getDeliveryStatus());
        // 发送时间保持为空，说明消息尚未真正送达。
        // 若失败时写入时间，补偿任务将无法区分未发送与已发送。
        // 空值与 FAILED 状态共同保证失败消息可被重新处理。
        assertNull(message.getSentTime());
    }
}
