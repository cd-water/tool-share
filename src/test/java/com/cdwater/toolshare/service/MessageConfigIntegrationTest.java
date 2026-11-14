package com.cdwater.toolshare.service;

// 消息配置测试涉及配置持久化、消息服务与通知偏好三块：
// LambdaQueryWrapper 用于清理按用户或标题前缀定位的数据；
// RestClient 走真实管理端接口，MessageService 则直接触发发送逻辑。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageConfig;
import com.cdwater.toolshare.module.message.MessageConfigMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

// 该类的三个测试关注点由类名统一约束：配置、持久化、发送行为。
// 测试不对短信服务发起真实调用，而是断言消息记录的渠道字段。
// 因此它不需要额外的外部服务，只需要数据库与 Spring 上下文。
// 所有断言都围绕“配置值如何影响落库消息”这一条主线展开。
// 从架构上看，配置读取与消息发送是两条独立路径：
// 管理端接口只负责校验并持久化配置；
// 发送服务在每次 notify 时通过 MessageConfigMapper 读取最新值。
// 这种设计让配置修改无需发布或重启即可生效，测试也据此设计。
// 若未来引入缓存，必须保证配置更新后缓存失效，否则本测试会失败。
// 本测试因此同时是“配置实时生效”这一非功能需求的回归保护。
// 数据库只有一行配置，读取失败时服务端回落到静态默认值。
// 默认值的语义必须是“可用且安全”：站内信可达，短信不外呼。
// 消息投递状态反映渠道能力：SMS 为 SENT，站内信为 DELIVERED。
// 这三个值构成测试断言的核心基线。
// 本测试关注“配置改动是否即时生效”，而不是单个 Controller 的字段校验：
// 管理端接口负责把配置写入数据库，发送逻辑在每次发送时重新读取配置。
// 因此测试先通过 HTTP 修改配置，再直接调用 MessageService 触发发送，
// 最后从数据库读取消息，核对实际使用的渠道与投递状态。
// 配置表只有一行（id=1），每个用例前后都删除该行，保证从默认值开始。
// 默认值约定为 SYSTEM 渠道且短信关闭，任何改动都必须显式持久化。
// 短信开启时消息标记 SENT，关闭后回退站内信并标记 DELIVERED。
// 该不变量保证运维可以在不重启应用的情况下切换消息通道。
/**
 * 消息渠道配置集成测试。
 * <p>
 * 测试范围：管理端读写消息配置，以及发送逻辑是否实时采用最新配置。
 * 依赖环境：随机端口完整应用、测试数据库与内存对象存储。
 * 关键不变量：配置持久化到数据库且默认值为 SYSTEM/短信关闭；
 * 开启短信后新消息走 SMS 通道；关闭后自动回退站内信，无需重启应用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class MessageConfigIntegrationTest extends AbstractIntegrationTest {

    // 前缀同时用于精确清理与人工排查，避免与种子消息混淆。
    // 消息标题前缀用于精确定位本测试产生的消息，清理时按前缀删除。
    // 标题与业务键都复用该前缀，便于定位测试产生的所有消息。
    // 前缀同时出现在断言失败信息中，排查时可以直接区分数据来源。
    // 该前缀也是消息标题的起始片段，清理查询依赖 likeRight 匹配。
    // 修改前缀时必须同步确认清理逻辑仍然有效。
    private static final String TITLE_PREFIX = "IT-MSG-CONFIG-";

    // 随机端口让测试可以与本地已运行实例共存。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    // 随机端口由 Spring 注入，客户端在 @BeforeEach 中据此构造。
    @LocalServerPort
    int port;

    // Mapper 由 Spring 注入，测试与生产使用同一套 MyBatis 映射。
    // 配置 Mapper 既用于准备数据，也用于直接核对持久化字段。
    /** 消息配置 Mapper，用于准备与核对持久化结果。 */
    @Autowired
    MessageConfigMapper configMapper;
    // 直接调用服务层可以覆盖 Controller 之外的发送决策逻辑。
    // 直接注入消息服务，可以绕过 Controller 触发真实的发送分支。
    /** 消息服务，直接触发发送以验证配置实时生效。 */
    @Autowired
    MessageService messageService;
    // 按业务键读取消息，避免依赖列表分页或消息 ID 顺序。
    // 消息 Mapper 用于按业务幂等键读取落库结果。
    /** 消息 Mapper，用于按业务键查询落库结果。 */
    @Autowired
    MessageMapper messageMapper;
    // 偏好数据在用例结束后清理，防止影响默认配置判断。
    // 通知偏好 Mapper 用于清理种子用户可能产生的偏好数据。
    /** 用户通知偏好 Mapper，测试后清理避免影响其他用例。 */
    @Autowired
    UserNotificationPrefMapper prefMapper;

    // 复用同一个 HTTP 客户端，保证鉴权头与 JSON 序列化设置一致。
    RestClient client;
    // JSON 解析器用于断言管理端配置接口的响应结构。
    final ObjectMapper om = new ObjectMapper();

    // 响应载体不引入额外依赖，record 足以表达状态码与 JSON 树。
    // 这样断言代码可以同时检查 HTTP 语义与业务字段。
    // 同时保留 HTTP 状态码与响应体，便于区分鉴权失败与业务校验失败。
    // 业务成功统一表现为 200 + code=A200，测试助手会统一校验。
    // 测试内部使用的轻量响应模型，仅覆盖本类需要的两个字段。
    record ApiResponse(int status, JsonNode body) {
    }

    // 每个用例都从“配置表为空”开始：
    // 服务端在读取不到配置时返回代码中的默认值，
    // 因此删除 id=1 就等价于恢复出厂设置。
    // 客户端也在此重建，避免跨用例复用失效的端口或连接。
    // 准备阶段的清理同样重要：配置表为空时服务端才会返回默认值。
    // 删除 id=1 相当于恢复“首次启动”的状态，避免上一个用例的 SMS 开关残留。
    // 客户端在每次用例前重建，端口取自当前 Spring 上下文。
    /**
     * 每个用例前创建 HTTP 客户端并删除已持久化的配置，
     * 使测试从默认配置开始，避免跨用例污染。
     */
    @BeforeEach
    void setUp() {
        // 指向随机端口，避免与本地服务冲突。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 清空配置行，让读取接口回落到默认配置。
        configMapper.deleteById(1L);
    }

    // 清理顺序与数据依赖无关，三个 delete 都是幂等操作。
    // 使用标题前缀而不是创建时间，避免受共享库时钟差异影响。
    // 通知偏好按用户维度删除，确保下个用例仍看到默认偏好。
    // 清理范围包括配置、种子用户的通知偏好与本测试创建的消息。
    // 消息按标题前缀删除，避免误删其他用例或种子数据。
    // 偏好按 userId=1 删除，保证下个用例从默认通知策略开始。
    /**
     * 用例结束后删除配置、用户通知偏好与本类产生的消息。
     */
    @AfterEach
    void cleanUp() {
        configMapper.deleteById(1L);
        // 删除种子用户的通知偏好，避免影响配置默认值判断。
        prefMapper.delete(new LambdaQueryWrapper<UserNotificationPref>()
                .eq(UserNotificationPref::getUserId, 1L));
        // 只删除标题以测试前缀开头的消息，缩小清理影响面。
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .likeRight(Message::getTitle, TITLE_PREFIX));
    }

    // 断言的顺序与业务链路一致：先配置、再持久化、再发送、最后回退。
    // 每个阶段都从数据库重新读取，而不是复用前一步的响应对象，
    // 这样可以发现“接口返回新值但数据库仍是旧值”的隐性问题。
    // 发送阶段使用不同的业务键，保证两次调用产生两条独立消息。
    // 回退阶段只关闭开关而不改渠道，验证发送逻辑以开关为准。
    // 这与运维“保留短信渠道配置但临时关闭”的真实操作一致。
    // 该用例完整覆盖配置读取、更新、持久化与发送时的实时采用：
    // 1) 首次读取应返回 SYSTEM + 短信关闭的默认值；
    // 2) 更新为 SMS + 开启后，响应与数据库都应反映新值；
    // 3) 发送消息应走 SMS 通道并标记 SENT；
    // 4) 关闭短信后再次发送，应自动回退 SYSTEM 并标记 DELIVERED。
    // 整个过程不重启 Spring 上下文，验证配置是发送时读取而非启动时缓存。
    // 消息使用业务幂等键定位，避免依赖自增 ID 或消息顺序。
    /**
     * 场景：管理员读取默认配置、改为短信通道并开启短信，然后发送消息；
     * 随后关闭短信开关再次发送消息。
     * 前置条件：配置表初始为空，发送使用种子用户及其通知偏好。
     * 断言目标：默认值为 SYSTEM 且短信关闭；更新后配置落库；
     * 开启短信时消息渠道为 SMS 且状态 SENT，关闭后自动回退 SYSTEM 并标记 DELIVERED。
     */
    @Test
    void config_is_persisted_and_used_by_sending() {
        // 管理员令牌用于访问配置读写接口。
        String admin = adminLogin();

        // 首次读取发生在清空配置之后，应返回代码中的默认值。
        // 默认值同时也是前端配置页的初始展示。
        // GET 配置接口不需要请求体，令牌通过 call 方法注入。
        // 返回值取 data 节点，断言只关注配置本身的字段。
        JsonNode initial = ok(call(HttpMethod.GET, "/api/admin/message-config", admin, null))
                .get("data");
        // 默认渠道为站内信 SYSTEM，表示没有外部短信通道时仍可送达。
        assertEquals("SYSTEM", initial.get("defaultChannel").asString());
        // 短信开关默认关闭，避免测试或新环境意外调用外部短信服务。
        assertEquals(false, initial.get("smsEnabled").asBoolean());

        // 通过管理端接口把渠道改为 SMS 并开启短信。
        // 请求体同时提交两个字段，验证配置更新是整体覆盖而非部分合并。
        // PUT 请求体携带完整配置；管理端接口按整体覆盖处理。
        JsonNode updated = ok(call(HttpMethod.PUT, "/api/admin/message-config", admin, Map.of(
                "defaultChannel", "SMS", "smsEnabled", true))).get("data");
        // 响应应回显更新后的渠道与开关，供前端立即刷新。
        assertEquals("SMS", updated.get("defaultChannel").asString());
        // smsEnabled 为布尔值，接口层不要把它序列化成数字。
        assertEquals(true, updated.get("smsEnabled").asBoolean());

        // 直接查询数据库，确认配置不是只保存在内存或响应中。
        // 直接读库可以排除接口层缓存或序列化造成的假象。
        MessageConfig stored = configMapper.selectById(1L);
        assertNotNull(stored, "配置应写入数据库");
        // 持久化字段与接口响应保持一致。
        assertEquals("SMS", stored.getDefaultChannel());
        // 数据库中使用 1/0 表示开关，读取时转换为整数比较。
        assertEquals(1, stored.getSmsEnabled());

        // 直接调用消息服务触发发送；业务键保证重复调用不会产生多条消息。
        // 标题带测试前缀，便于 @AfterEach 精确清理。
        // 此时配置为 SMS，发送逻辑应选择短信通道。
        // notifyUserOnce 使用业务键去重，重复调用不会产生重复消息。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SMS",
                "短信配置生效", TITLE_PREFIX + "SMS-KEY");
        // 按业务幂等键读取落库消息，确认发送分支实际采用 SMS。
        // 发送后立刻按业务键查询，确认消息已落库。
        Message sms = messageByKey(TITLE_PREFIX + "SMS-KEY");
        assertNotNull(sms);
        // 渠道字段是发送时的决策结果，必须为 SMS。
        assertEquals("SMS", sms.getChannel());
        // 短信通道在测试环境中视为外部通道，发送后标记 SENT。
        assertEquals("SENT", sms.getDeliveryStatus());

        // 再次更新配置：保持 SMS 渠道但关闭短信开关。
        // 业务语义是“优先短信，但当前禁用”，发送时应自动回退。
        // 只关闭短信开关，默认渠道仍保留 SMS，用于验证回退优先级。
        ok(call(HttpMethod.PUT, "/api/admin/message-config", admin, Map.of(
                "defaultChannel", "SMS", "smsEnabled", false)));
        // 关闭短信后再次发送，业务键与标题均不同，避免命中上一条消息。
        messageService.notifyUserOnce(1L, MessageType.RENT_DUE, TITLE_PREFIX + "SYSTEM",
                "短信关闭后回退站内", TITLE_PREFIX + "SYSTEM-KEY");
        // 读取回退场景产生的消息。
        Message system = messageByKey(TITLE_PREFIX + "SYSTEM-KEY");
        assertNotNull(system);
        // 配置关闭后应回退到站内信 SYSTEM。
        assertEquals("SYSTEM", system.getChannel());
        // 站内信直接落库，因此状态为 DELIVERED。
        assertEquals("DELIVERED", system.getDeliveryStatus());
    }

    // 该助手只返回单条记录；业务键在数据模型上应保持唯一。
    // 业务幂等键是消息表上的唯一业务标识，适合作为测试定位条件。
    // 查询结果为空时返回 null，由调用方用 assertNotNull 断言。
    // 按业务幂等键查询消息，是本测试判断实际渠道的唯一依据。
    // 相比遍历全部消息，按 key 查询不受其他用例数据影响。
    /** 辅助方法：按业务幂等键查询消息，便于判断实际使用的渠道。 */
    private Message messageByKey(String key) {
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, key));
    }

    // 成功响应必须同时满足传输层与业务层约定。
    // 附带原始响应体可以让失败信息直接显示服务端返回。
    // 统一的成功响应断言：HTTP 200 与业务码 A200 必须同时满足。
    // 失败消息携带原始响应体，便于定位字段或权限变化。
    // 返回响应体供调用方继续读取 data 字段。
    /**
     * 辅助方法：断言 HTTP 200 与业务码 A200，并返回响应体。
     * 任一断言失败都会附带原始响应，便于定位契约变化。
     */
    private JsonNode ok(ApiResponse response) {
        // HTTP 层成功是业务成功的前提。
        assertEquals(200, response.status(), response.body().toString());
        // 业务码 A200 表示请求被正常处理，而不是仅网络层成功。
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    // 登录令牌只在本测试内使用，不写入任何持久化状态。
    // 种子管理员的密码来自测试数据初始化脚本。
    // 使用种子管理员账号登录；密码为测试环境固定值。
    // 登录响应中的 tokenValue 用于后续 Bearer 鉴权。
    /** 辅助方法：管理员登录并返回令牌。 */
    private String adminLogin() {
        // 管理端登录接口与用户端分离，返回的令牌不能访问用户端接口。
        ApiResponse response = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", "Aa123456"));
        // 登录失败时直接暴露响应体，便于排查种子数据是否初始化。
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 发送请求前统一设置鉴权头与内容类型，避免各调用点重复。
    // 响应解析遵循“空体即空对象”的约定，兼容无内容响应。
    // 所有管理端请求都通过该方法发送，统一处理令牌与 JSON 请求体。
    // 响应流被完整读取并解析，空响应按空对象处理，避免解析异常。
    // 该方法不主动断言业务码，由调用方决定成功或失败语义。
    /**
     * 辅助方法：发送请求并解析 JSON 响应，统一保留状态码与响应体。
     */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 先构造请求规格，后续按需附加鉴权头与请求体。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 管理端接口统一使用 Bearer 令牌。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // GET 请求没有请求体时不设置 Content-Type。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 在 exchange 回调中读取响应，避免 RestClient 对 4xx/5xx 抛异常。
        return spec.exchange((request, response) -> {
            // try-with-resources 确保响应流关闭，防止连接泄漏。
            try (InputStream in = response.getBody()) {
                // 以 UTF-8 解码响应体，中文错误信息不会乱码。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 同时保留状态码与 JSON 树，便于后续分别断言。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // IO 异常属于测试基础设施问题，包装为运行时异常直接失败。
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
