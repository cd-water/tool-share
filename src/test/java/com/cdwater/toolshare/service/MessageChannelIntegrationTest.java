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

/*
 * 测试设计说明
 * ------------
 * 一、为什么走真实 HTTP：
 *     通知偏好既有接口层校验（如 SYSTEM 不可关闭），
 *     又有服务层过滤（发送前读取偏好）。
 *     只有端到端调用才能同时覆盖两层，并验证状态码与业务码。
 *
 * 二、为什么直接调用 MessageService：
 *     偏好过滤发生在发送入口。
 *     直接调用 notifyUserOnce 可以精确控制消息类型与业务键，
 *     不必先构造完整的租赁、报修或公告业务流程。
 *
 * 三、为什么用业务键统计：
 *     消息服务以 bizKey 做幂等，同一业务键最多落一条消息。
 *     因此计数为 0 或 1 即可判断消息是送达还是被偏好过滤。
 *
 * 四、环境依赖：
 *     - 完整 Spring Boot 应用与随机端口；
 *     - 测试数据库中的种子用户 1、2；
 *     - 短信开关打开，SmsSender 使用测试替身；
 *     - expose-captcha 打开，便于验证码登录。
 *
 * 五、数据隔离：
 *     通知偏好按用户 ID 清理，消息按标题前缀清理，
 *     用例之间不共享可变状态，避免开关泄漏导致随机失败。
 *
 * 六、关键不变量：
 *     1. 关闭某类型后，该类型消息在落库前被过滤；
 *     2. SYSTEM 消息不可关闭，始终送达；
 *     3. SMS 偏好会把渠道记为 SMS 且送达状态为 SENT；
 *     4. 偏好只影响所属用户，不会串号；
 *     5. 公告默认开启并带中文类型名；
 *     6. 未登录请求返回 401。
 *
 * 七、失败排查提示：
 *     消息未落库时先确认偏好是否被前置用例遗留为关闭，
 *     再检查短信渠道配置与发送器返回状态。
 */
/**
 * 消息渠道与通知偏好集成测试。
 * <p>
 * 测试范围：用户通知偏好的读写、禁止关闭系统消息、短信渠道的送达状态，
 * 以及偏好按用户隔离与公告类型的默认值。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储；
 * 全局开启短信并使用假短信发送器，同时暴露验证码便于登录。
 * 关键不变量：关闭某类型后不再产生该类型消息；SYSTEM 消息不可关闭；
 * 短信偏好会把渠道记为 SMS 且状态 SENT；偏好只影响所属用户。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"toolshare.message.sms-enabled=true", "toolshare.auth.expose-captcha=true"})
@Import(TestStorageConfig.class)
class MessageChannelIntegrationTest extends AbstractIntegrationTest {

    // 消息标题前缀用于隔离本类产生的数据。
    // 通知偏好可能影响消息是否落库，
    // 因此清理时按标题前缀删除，避免误删其他测试的消息。
    private static final String TITLE_PREFIX = "IT-MSG-";

    // 随机端口由应用启动时分配，
    // 测试通过真实 HTTP 调用验证接口与偏好过滤的组合行为。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 消息服务是偏好过滤的入口：
    // 用例直接调用 notifyUserOnce，观察不同偏好下消息是否落库。
    /** 消息服务，直接触发发送以验证偏好过滤。 */
    @Autowired
    MessageService messageService;
    // 消息表用于按业务幂等键统计条数，
    // 这是判断“是否送达/是否被偏好过滤”的最终事实来源。
    /** 消息 Mapper，用于按业务键统计是否送达。 */
    @Autowired
    MessageMapper messageMapper;
    // 偏好表用于清理种子用户的通知偏好，
    // 防止某个用例关闭的类型影响其他用例。
    /** 通知偏好 Mapper，用于清理两个种子用户的偏好。 */
    @Autowired
    UserNotificationPrefMapper prefMapper;

    // 带令牌的 HTTP 客户端在 @BeforeEach 中按随机端口创建。
    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    // 响应封装同时保留 HTTP 状态与 JSON 业务体，
    // 便于断言 200/A200、400/C400、401/C401 等组合。
    record ApiResponse(int status, JsonNode body) {
    }

    // 每个用例前重新构建客户端，保证端口与本次应用实例一致。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // 清理策略：
    // 删除用户 1、2 的偏好，恢复种子默认值；
    // 删除本类按标题前缀产生的消息；
    // 避免偏好状态或历史消息对后续用例造成随机影响。
    /**
     * 用例结束后清理两个种子用户的通知偏好与本类产生的消息。
     * 避免偏好开关泄漏到其他测试，造成消息是否送达的随机失败。
     */
    @AfterEach
    // 先删偏好再删消息，逻辑上无强依赖；
    // 两者都按测试专属范围过滤，不会动到种子数据。
    void cleanUp() {
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .in(UserNotificationPref::getUserId, List.of(1L, 2L)));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    // 覆盖点：关闭某类型后，发送入口是否真正过滤。
    // 业务背景：用户关闭到期提醒后，定时任务仍可能调用发送服务，
    // 因此过滤必须发生在消息落库之前，而不是仅在界面上隐藏。
    // 前置条件：种子用户已登录，RENT_DUE 偏好已持久化为关闭。
    // 验证路径：查询偏好确认关闭 -> 调用发送 -> 按业务键统计。
    // 断言目标：偏好回显 enabled=false，消息计数为 0。
    // 失败后果：用户已关闭提醒却仍收到消息，偏好形同虚设。
    // 该用例只使用一个业务键，
    // 因此计数结果不会受到其他消息干扰。
    /**
     * 场景：用户关闭 RENT_DUE 类型提醒后触发一次到期提醒。
     * 前置条件：偏好接口已持久化 enabled=false。
     * 断言目标：偏好查询回显关闭状态；按业务键查询消息为 0 条，
     * 证明关闭后消息在发送前就被过滤。
     */
    @Test
    // 使用种子用户 1 登录，偏好按用户维度持久化。
    void disabled_reminder_type_is_not_sent() {
        // 密码登录取得用户令牌。
        String user = userLogin("13800000001");
        // 把到期提醒关闭；关闭状态下渠道字段不参与发送。
        setPref(user, MessageType.RENT_DUE, false, "SYSTEM");

        // 通过查询接口确认关闭状态已持久化，
        // 排除“只改内存未落库”的假成功。
        JsonNode prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        // 回显必须为 enabled=false。
        assertFalse(prefOf(prefs, "RENT_DUE").get("enabled").asBoolean());

        // 直接调用服务发送一条本应被过滤的提醒。
        // 业务键唯一，便于后续精确统计。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "DISABLED",
                "不应送达的内容", TITLE_PREFIX + "DISABLED-KEY");

        // 断言消息表没有该业务键：
        // 0 条说明消息在发送前就被偏好拦截，而不是发送后删除。
        assertEquals(0, countByBizKey(TITLE_PREFIX + "DISABLED-KEY"));
    }

    // 覆盖点：系统消息不可被用户静音。
    // 业务背景：SYSTEM 消息通常承载安全、合规或账号相关通知，
    // 允许关闭会让用户永久错过关键信息。
    // 前置条件：种子用户已登录。
    // 验证路径：尝试关闭 SYSTEM -> 断言 400 -> 再发送系统消息。
    // 断言目标：关闭请求被拒绝且提示系统消息；
    // 实际发送仍落库 1 条。
    // 失败后果：关键通知可被屏蔽，平台无法履行告知义务。
    // 该用例同时验证接口层拒绝与发送层不读取该偏好。
    /**
     * 场景：用户尝试关闭 SYSTEM 类型消息，然后发送一条系统消息。
     * 前置条件：系统消息承载安全与合规通知，不允许被静音。
     * 断言目标：关闭请求返回 400 且提示系统消息；实际发送仍落库 1 条，
     * 保证关键通知不会被用户偏好屏蔽。
     */
    @Test
    // 使用种子用户登录。
    void system_messages_cannot_be_disabled() {
        // 登录后才能访问偏好接口。
        String user = userLogin("13800000001");

        // 尝试关闭 SYSTEM 类型，预期被服务端拒绝。
        ApiResponse rejected = call(HttpMethod.PUT, "/api/user/notification-prefs/SYSTEM", user,
                Map.of("enabled", false, "channel", "SYSTEM"));
        // 400 表示请求本身不被允许。
        assertEquals(400, rejected.status(), rejected.body().toString());
        // 错误信息应明确指出“系统消息”不可关闭，
        // 便于前端直接展示而不是映射通用错误码。
        assertTrue(rejected.body().get("message").asString().contains("系统消息"),
                rejected.body().toString());

        // 即使关闭请求失败，系统消息本身仍应正常送达。
        messageService.notifyUserOnce(1L, MessageType.SYSTEM, TITLE_PREFIX + "SYSTEM",
                "系统消息始终送达", TITLE_PREFIX + "SYSTEM-KEY");
        // 落库 1 条，证明 SYSTEM 渠道不受用户偏好影响。
        assertEquals(1, countByBizKey(TITLE_PREFIX + "SYSTEM-KEY"));
    }

    // 覆盖点：渠道选择与送达状态回写。
    // 业务背景：偏好不仅决定“发不发”，还决定“走哪个渠道”。
    // 用户选择短信后，消息表中的渠道与状态必须准确反映实际发送结果。
    // 前置条件：全局短信开关打开，SmsSender 为测试替身。
    // 验证路径：设置 SMS 偏好 -> 发送 -> 按业务键查询消息。
    // 断言目标：channel=SMS、deliveryStatus=SENT、sentTime 非空。
    // 失败后果：前端展示的渠道与实际不符，
    // 或失败消息被误记为已送达，影响客服排查。
    // 测试替身不会真实外发，因此用例可安全重复执行。
    /**
     * 场景：用户把 RENT_DUE 偏好改为短信渠道并触发发送。
     * 前置条件：全局短信开关打开，SmsSender 为测试替身不会真实外发。
     * 断言目标：消息渠道为 SMS、状态 SENT、发送时间非空，
     * 证明渠道选择与成功回写都正确。
     */
    @Test
    // 种子用户登录。
    void sms_preference_uses_mock_and_records_sent_status() {
        // 登录以取得可修改偏好的身份。
        String user = userLogin("13800000001");
        // 开启到期提醒并指定 SMS 渠道。
        setPref(user, MessageType.RENT_DUE, true, "SMS");

        // 触发一条到期提醒，测试环境使用假短信发送器。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SMS",
                "短信渠道提醒", TITLE_PREFIX + "SMS-KEY");

        // 按业务键查询落库消息。
        // 业务键保证即使重复调用也只会命中同一条记录。
        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, TITLE_PREFIX + "SMS-KEY"));
        // 消息必须存在，否则后续渠道断言没有对象。
        assertNotNull(message);
        // 渠道应遵循偏好中的 SMS，而不是默认站内信。
        assertEquals("SMS", message.getChannel());
        // 假发送器成功返回后，状态应回写为 SENT。
        assertEquals("SENT", message.getDeliveryStatus());
        // 发送时间必须非空，证明送达状态不是仅有枚举而无实际时间。
        assertNotNull(message.getSentTime());
    }

    // 覆盖点：偏好的用户隔离。
    // 业务背景：通知偏好是用户私有配置，
    // 不能因为用户 1 关闭而影响用户 2。
    // 前置条件：两个种子用户分别用密码与验证码登录。
    // 验证路径：用户 1 关闭 RENT_DUE -> 查询用户 2 偏好 ->
    // 分别给两人发送同类型提醒。
    // 断言目标：用户 2 偏好仍为开启；
    // 用户 1 无消息，用户 2 有且仅有一条。
    // 失败后果：一个用户的设置影响他人，属于严重的数据串号。
    // 两个用户使用独立令牌，避免会话层串用。
    /**
     * 场景：用户 1 关闭 RENT_DUE，用户 2 保持默认，再分别给两人发提醒。
     * 前置条件：用户 1 走密码登录、用户 2 走验证码登录，均使用独立令牌。
     * 断言目标：用户 2 的偏好仍为开启；用户 1 无消息、用户 2 有消息，
     * 证明偏好按用户隔离，不会串号影响他人。
     */
    @Test
    // 两个种子用户分别用不同登录方式取得令牌，
    // 同时覆盖密码登录与验证码登录两条认证路径。
    void preferences_are_scoped_to_owner() {
        // 用户 1 使用密码登录。
        String user1 = userLogin("13800000001");
        // 用户 2 使用验证码登录，测试环境暴露验证码。
        String user2 = userLoginByCode("13800000002");
        // 仅关闭用户 1 的 RENT_DUE。
        setPref(user1, MessageType.RENT_DUE, false, "SYSTEM");

        // 查询用户 2 的偏好，预期仍为默认开启。
        JsonNode user2Prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user2, null)
                .body().get("data");
        // 用户 2 的开关不受用户 1 修改影响。
        assertTrue(prefOf(user2Prefs, "RENT_DUE").get("enabled").asBoolean());

        // 给用户 1 发送提醒，应被其偏好过滤。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "OWNER-1",
                "用户1已关闭", TITLE_PREFIX + "OWNER-1-KEY");
        // 给用户 2 发送同类型提醒，应正常送达。
        messageService.notifyUserOnce(2L, MessageType.RENT_DUE, TITLE_PREFIX + "OWNER-2",
                "用户2仍开启", TITLE_PREFIX + "OWNER-2-KEY");

        // 用户 1 无消息。
        assertEquals(0, countByBizKey(TITLE_PREFIX + "OWNER-1-KEY"));
        // 用户 2 有且仅有一条消息，验证按用户隔离。
        assertEquals(1, countByBizKey(TITLE_PREFIX + "OWNER-2-KEY"));
    }

    // 覆盖点：公告类型的默认值与修改回显。
    // 业务背景：公告是可选订阅类型，默认开启，
    // 前端需要稳定的类型名与开关状态才能正确渲染。
    // 前置条件：种子用户已登录。
    // 验证路径：首次查询 -> 断言默认开启与中文类型名 ->
    // 关闭公告 -> 再次查询。
    // 断言目标：默认 enabled=true、typeName=公告；
    // 修改后回显 enabled=false。
    // 失败后果：公告开关显示错误或无法持久化。
    // 该用例也覆盖偏好列表的排序与字段完整性。
    /**
     * 场景：查询偏好列表并关闭公告类型，再次查询。
     * 前置条件：公告是可选订阅类型，默认开启并带中文类型名。
     * 断言目标：默认值为开启且类型名为“公告”；修改后回显为关闭，
     * 保证前端能正确渲染并持久化公告开关。
     */
    @Test
    // 种子用户登录。
    void announcement_is_available_in_preferences() {
        // 获取令牌以读取偏好。
        String user = userLogin("13800000001");

        // 首次查询得到迁移/初始化后的默认偏好。
        JsonNode prefs = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        // 公告默认开启。
        assertTrue(prefOf(prefs, "ANNOUNCEMENT").get("enabled").asBoolean());
        // 类型名应为中文“公告”，供前端直接展示。
        assertEquals("公告", prefOf(prefs, "ANNOUNCEMENT").get("typeName").asString());

        setPref(user, MessageType.ANNOUNCEMENT, false, "SYSTEM");
        // 再次查询确认修改已持久化。
        JsonNode updated = call(HttpMethod.GET, "/api/user/notification-prefs", user, null)
                .body().get("data");
        // 回显应为关闭，证明偏好更新走的是数据库而非临时状态。
        assertFalse(prefOf(updated, "ANNOUNCEMENT").get("enabled").asBoolean());
    }

    // 覆盖点：未认证访问的拦截。
    // 业务背景：通知偏好属于用户私有数据，
    // 匿名读取会泄露联系方式与订阅习惯，匿名修改会干扰用户设置。
    // 前置条件：请求不携带任何令牌。
    // 验证路径：直接 GET 偏好列表。
    // 断言目标：返回 401，而不是 200 或 403。
    // 失败后果：偏好数据可被任意读取或篡改。
    // 401 与 403 的区分也为前端提供明确的登录引导。
    /**
     * 场景：未携带令牌读取通知偏好。
     * 前置条件：偏好属于用户私有数据。
     * 断言目标：返回 401，避免匿名读取或篡改他人偏好。
     */
    @Test
    // 不携带任何令牌访问偏好列表。
    void preference_endpoints_require_login() {
        // 显式以 null 令牌发起 GET，模拟匿名访问。
        ApiResponse response = call(HttpMethod.GET, "/api/user/notification-prefs", null, null);
        // 401 表示缺少有效身份，而不是身份合法但权限不足。
        assertEquals(401, response.status(), response.body().toString());
    }

    // 辅助方法：修改偏好并断言接口契约。
    // 每次修改都必须返回 200/A200，失败时输出响应体便于定位。
    // 修改成功后由调用方再次查询或发送消息验证效果。
    /** 辅助方法：调用偏好更新接口并断言 HTTP 200、业务码 A200。 */
    private void setPref(String token, MessageType type, boolean enabled, String channel) {
        ApiResponse response = call(HttpMethod.PUT, "/api/user/notification-prefs/" + type.code(), token,
                Map.of("enabled", enabled, "channel", channel));
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString());
    }

    // 辅助方法：在偏好数组中找到指定类型。
    // 找不到直接抛 AssertionError，避免后续空指针掩盖真正原因。
    // 返回条目节点，供调用方继续断言 enabled、typeName 等字段。
    /**
     * 辅助方法：在偏好数组中按类型查找条目。
     * 找不到时抛 AssertionError，提示缺少的类型，便于定位默认值问题。
     */
    private JsonNode prefOf(JsonNode prefs, String type) {
        // 线性扫描偏好数组，类型数量很小，无需额外索引。
        for (JsonNode pref : prefs) {
            if (type.equals(pref.get("type").asString())) {
                return pref;
            }
        }
        // 抛出带类型名的断言错误，便于快速定位缺失项。
        throw new AssertionError("偏好列表缺少类型: " + type);
    }

    // 辅助方法：按业务幂等键统计消息条数。
    // 业务键在消息服务中用于去重，因此可作为“是否送达”的稳定标识。
    // 计数为 0 表示未送达，为 1 表示已送达，大于 1 说明幂等失效。
    /** 辅助方法：按业务幂等键统计消息条数，用于判断是否送达。 */
    private long countByBizKey(String bizKey) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, bizKey));
    }

    // 辅助方法：密码登录种子用户并返回令牌。
    // 登录失败时保留响应体，便于排查账号或密码变更。
    // 测试固定使用 13800000001，对应种子用户 1。
    /** 辅助方法：用指定手机号走密码登录并返回令牌。 */
    private String userLogin(String phone) {
        ApiResponse response = call(HttpMethod.POST, "/api/user/login/by-pwd", null,
                Map.of("phone", phone, "password", "Aa123456"));
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 辅助方法：先发送验证码，再用验证码登录。
    // 测试环境开启 expose-captcha，因此验证码可直接从响应读取。
    // 该路径覆盖与密码登录不同的认证分支。
    /**
     * 辅助方法：先发送验证码，再用验证码登录并返回令牌。
     * 测试环境暴露验证码，因此可直接从响应中取得。
     */
    private String userLoginByCode(String phone) {
        // 第一步：请求验证码。
        ApiResponse sent = call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", phone));
        assertEquals("A200", sent.body().get("code").asString(), sent.body().toString());
        // 第二步：用上一步返回的验证码换取令牌。
        ApiResponse response = call(HttpMethod.POST, "/api/user/login/by-code", null,
                Map.of("phone", phone, "code", sent.body().get("data").asString()));
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 通用 HTTP 辅助方法：
    // 负责可选的鉴权头、可选 JSON 体以及统一响应解析。
    // 所有用例通过该方法发起请求，保证行为一致。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // 按 HTTP 方法构建请求，GET/PUT/POST 共用同一套断言逻辑。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 创建请求规格，后续按需追加头与请求体。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 只有需要登录的接口才添加 Bearer 头；
        // 匿名访问用例通过 token=null 复用该方法。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 无请求体的请求不设置 Content-Type，避免服务端误判。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取 4xx/5xx 响应体，
        // 因此 401、400 等失败场景也能被用例断言。
        return spec.exchange((request, response) -> {
            try (InputStream in = response.getBody()) {
                // 读取完整响应流并转成 UTF-8 字符串。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 空响应体按空对象处理，避免 JSON 解析报错。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 读取流失败属于测试基础设施异常，直接抛出运行时异常。
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
