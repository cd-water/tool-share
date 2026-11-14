// 待支付租赁单超时回收集成测试：验证过期取消与档期释放。
package com.cdwater.toolshare.service;

// MyBatis-Plus 条件构造器：按名称前缀与工具 ID 删除测试数据。
// 使用 Lambda 写法避免列名字符串，重构时更安全。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// 集成测试基类：提供共享的 Spring 上下文、数据库与清理支持。
// 继承后可复用测试环境，聚焦本类的租期过期场景。
import com.cdwater.toolshare.AbstractIntegrationTest;
// 测试存储配置：以内存实现替换对象存储，避免访问外部 S3。
// 创建工具时上传的图片只落内存，测试结束即释放。
import com.cdwater.toolshare.TestStorageConfig;
// 报修实体：清理工具前需先删除其关联的报修记录。
// 过期扫描本身不产生报修，仅用于保持外键一致性。
import com.cdwater.toolshare.module.damage.DamageReport;
// 报修 Mapper：按工具 ID 删除测试报修数据。
// 属于清理链路的一环，避免遗留脏数据阻塞后续用例。
import com.cdwater.toolshare.module.damage.DamageReportMapper;
// 租赁单实体：过期后断言状态与押金字段。
// 取消逻辑会修改状态，需要重新查库确认持久化结果。
import com.cdwater.toolshare.module.rental.Rental;
// 租赁单 Mapper：按 ID 回查过期订单，并在清理时按工具删除。
// 直接读库可绕过接口缓存，验证真实落库状态。
import com.cdwater.toolshare.module.rental.RentalMapper;
// 租赁服务：暴露过期扫描方法，测试直接触发而无需等待调度。
// 这样可以精确控制“创建后立即过期”的时序。
import com.cdwater.toolshare.module.rental.RentalService;
// 工具实体：清理时按名称前缀查询本类创建的工具。
// 工具是租赁与报修数据的根节点，需最后删除。
import com.cdwater.toolshare.module.tool.Tool;
// 工具日志实体：删除工具前先清理关联日志。
// 日志记录了工具状态变更，测试数据也需一并回收。
import com.cdwater.toolshare.module.tool.ToolLog;
// 工具日志 Mapper：按工具 ID 删除日志记录。
// 保证共享测试库不残留过期用例的审计数据。
import com.cdwater.toolshare.module.tool.ToolLogMapper;
// 工具 Mapper：创建测试工具、审核通过并在结束后删除。
// 工具需处于 APPROVED 状态才能被用户下单。
import com.cdwater.toolshare.module.tool.ToolMapper;
// 每个用例后执行清理，防止测试数据污染其他用例。
// 即使断言失败也会执行，保证环境可重复。
import org.junit.jupiter.api.AfterEach;
// 每个用例前准备 HTTP 客户端。
// 客户端指向随机端口，模拟真实前端调用。
import org.junit.jupiter.api.BeforeEach;
// JUnit 用例注解。
// 标记过期回收集成场景为独立测试。
import org.junit.jupiter.api.Test;
// Spring 依赖注入：注入 Mapper、服务与当前测试端口。
// 测试上下文与生产使用同一套 Bean。
import org.springframework.beans.factory.annotation.Autowired;
// 启动完整应用上下文并使用随机端口。
// 同时覆盖支付超时为 0，使新订单立即过期。
import org.springframework.boot.test.context.SpringBootTest;
// 注入随机端口，供 RestClient 拼接本地地址。
// 避免固定端口冲突，支持并发测试。
import org.springframework.boot.test.web.server.LocalServerPort;
// 导入测试存储配置，替换默认对象存储实现。
// 仅影响测试上下文，不改变生产代码。
import org.springframework.context.annotation.Import;
// HTTP 方法枚举：用于构造 REST 请求。
// 与真实管理端/用户端接口路径保持一致。
import org.springframework.http.HttpMethod;
// 媒体类型常量：请求体按 JSON 发送。
// 与后端接口的 Content-Type 约定一致。
import org.springframework.http.MediaType;
// Spring 同步 HTTP 客户端：不启动浏览器即可端到端调用接口。
// 直接验证鉴权、状态码与响应结构。
import org.springframework.web.client.RestClient;
// JSON 树模型：按字段路径断言响应内容。
// 无需为每个响应定义 DTO。
import tools.jackson.databind.JsonNode;
// JSON 解析器：把原始响应体解析为 JsonNode。
// 空响应体按空对象处理，避免解析异常。
import tools.jackson.databind.ObjectMapper;

// IO 异常：读取响应体时可能抛出。
// 捕获后转为非受检异常，简化 Lambda 写法。
import java.io.IOException;
// 输入流：用于读取接口返回的字节。
// try-with-resources 保证响应体及时关闭。
import java.io.InputStream;
// 字符集常量：响应体按 UTF-8 解码。
// 与接口返回编码一致，避免中文乱码。
import java.nio.charset.StandardCharsets;
// 本地时间：构造预约起止时间与过期判定。
// 使用本地时区，与业务语义一致。
import java.time.LocalDateTime;
// 日期格式化器：把时间序列化为接口要求的字符串。
// 固定到秒，避免纳秒精度影响档期比较。
import java.time.format.DateTimeFormatter;
// 集合工具：构造 imageUrls 空列表。
// 工具创建接口要求该字段存在。
import java.util.List;
// Map 工具：快速拼装请求体与登录参数。
// 使用不可变 Map 减少测试中的意外修改。
import java.util.Map;

// 静态断言：校验状态、押金状态与业务码。
// 失败时消息中带上实际响应，便于排查。
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 待支付租赁单超时回收集成测试。
 * <p>
 * 测试范围：待支付订单超过配置时限后由定时任务取消，并释放对应工具档期。
 * 依赖环境：随机端口完整应用、测试数据库与内存对象存储；
 * 本类把 pending-payment-timeout-minutes 覆盖为 0，使订单立即过期。
 * 关键不变量：过期只影响待支付订单；押金状态保持未支付；
 * 取消后同一时间段可以重新下单，说明档期占用已释放。
 * <p>
 * 超时配置被覆盖为 0 分钟，因此订单创建后无需等待即可被扫描取消。
 * 过期扫描直接调用服务方法，避免测试等待定时任务的下一次触发。
 * 断言重点：订单状态、押金状态，以及同一档期能否重新下单。
 * 只有待支付订单会被取消，已支付或已取件订单不受影响。
 * 取消后档期立即释放，体现资源占用与订单状态的一致性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.rental.pending-payment-timeout-minutes=0")
@Import(TestStorageConfig.class)
class RentalExpiryIntegrationTest extends AbstractIntegrationTest {

    // 统一的时间格式：与接口约定一致，精确到秒。
    // 去掉纳秒可避免同一时刻的精度差异导致档期比较失败。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 测试工具统一前缀：清理时按前缀批量删除。
    // 前缀同时用于生成唯一工具名称，避免档期冲突。
    private static final String PREFIX = "IT-EXPIRE-";

    // 随机端口由 Spring 在启动时分配，测试内所有请求都复用它。
    /** 随机端口，供 RestClient 拼接本地地址。 */
    @LocalServerPort
    int port;

    // 工具 Mapper：插入测试工具并回查审核后的状态。
    // 工具创建后必须审核通过才可被用户预约。
    /** 工具表 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：删除工具前先清理其状态变更日志。
    /** 工具日志 Mapper，清理工具时级联删除日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;
    // 租赁单 Mapper：回查过期扫描后的订单状态。
    /** 租赁单 Mapper，用于断言过期后的状态并清理数据。 */
    @Autowired
    RentalMapper rentalMapper;
    // 报修 Mapper：清理工具关联的报修记录，保持数据一致。
    /** 报修 Mapper，用于清理测试工具关联的报修记录。 */
    @Autowired
    DamageReportMapper damageReportMapper;
    // 租赁服务：直接调用 expirePending 触发一轮过期处理。
    // 相比等待调度，手动触发让测试更快、更稳定。
    /** 直接调用过期扫描，无需等待真实定时任务触发。 */
    @Autowired
    RentalService rentalService;

    // HTTP 客户端在每个用例前按随机端口重建。
    // ObjectMapper 复用于解析所有响应体，避免重复创建。
    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    /**
     * 每个用例前创建指向随机端口的 HTTP 客户端。
     * 指向当前随机端口的客户端，所有接口调用都经由它发出。
     * 每个用例独立创建，避免上一个用例的状态残留。
     */
    @BeforeEach
    void setUp() {
        // 使用随机端口拼接 baseUrl，等价于浏览器访问测试实例。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按名称前缀清理工具及其关联的报修、租赁与日志，
     * 避免测试数据影响后续用例或共享测试库。
     * <p>
     * 先按前缀找到本类创建的工具，再依次删除报修、租赁、日志与工具本身。
     * 删除顺序遵循外键依赖，避免约束冲突。
     */
    @AfterEach
    void cleanUp() {
        // 按统一前缀查询本类创建的工具，限定清理范围。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        // 逐个工具清理其关联数据，避免跨用例残留。
        for (Tool tool : tools) {
            // 先删报修记录，解除对工具的引用。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 再删租赁单，解除工具与租户的档期占用。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 删除工具日志，保留数据库审计表干净。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 最后删除工具，完成整条依赖链的回收。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：用户创建一个待支付租赁单，随后直接触发过期扫描。
     * 前置条件：待支付超时被配置为 0 分钟，订单在创建后立即满足过期条件。
     * 断言目标：订单状态变为 CANCELLED、押金仍为 UNPAID；
     * 再次提交同一工具同一档期的预约能够成功，证明档期已释放。
     * <p>
     * 步骤一：管理员创建并审核工具，用户创建待支付订单。
     * 步骤二：调用 expirePending 让配置为 0 分钟的订单立即过期。
     * 步骤三：回查订单状态，并用相同档期再次下单验证档期释放。
     * 关键断言：押金保持 UNPAID，说明取消没有触碰支付状态。
     */
    @Test
    void expired_pending_payment_releases_the_slot() {
        // 管理员登录，用于创建与审核工具。
        String admin = adminLogin();
        // 种子用户登录，取得下单令牌。
        String user = userLogin();
        // 创建并审核一个工具，确保用户可预约。
        long toolId = createApprovedTool(admin, "expire");
        // 构造两天后开始、租期一天的预约请求体，便于复用两次下单。
        Map<String, Object> booking = booking(toolId);

        // 从响应 data 中取出租赁单 ID，供后续回查。
        // 下单接口返回 A200 且包含新订单主键。
        long rentalId = call(HttpMethod.POST, "/api/user/rentals", user, booking)
                .get("data").get("id").asLong();
        // 扫描前订单仍为待支付，这里直接触发过期，不再单独断言初始状态。

        // 触发过期扫描：配置为 0 分钟，所有待支付订单都会立即到期。
        // 扫描应只处理 PENDING_PAYMENT 状态，不影响其他订单。
        rentalService.expirePending();

        // 回查数据库中的订单，验证取消结果已持久化。
        Rental expired = rentalMapper.selectById(rentalId);
        // 订单状态应从待支付变为已取消。
        assertEquals("CANCELLED", expired.getStatus());
        // 押金仍为未支付，取消不会产生退款或扣款。
        assertEquals("UNPAID", expired.getDepositStatus());
        // 两次下单使用同一个请求体，唯一的区别是第一次订单已被取消。

        // 用同一工具、同一档期再次下单，若成功说明档期已释放。
        // 若档期未释放，此请求会因时间冲突而失败。
        JsonNode rebooked = call(HttpMethod.POST, "/api/user/rentals", user, booking);
        // 断言业务码为 A200，确认重新预约成功。
        assertEquals("A200", rebooked.get("code").asString(), rebooked.toString());
    }

    /**
     * 辅助方法：构造两天后开始、租期一天的预约请求体。
     * 使用固定格式化时间避免纳秒精度影响档期比较。
     * 时间设为两天后，避开“不可预约过去时间”的校验。
     * 固定到秒并去掉纳秒，保证两次请求的档期完全一致。
     */
    private Map<String, Object> booking(long toolId) {
        // 使用 Map.of 组装不可变请求体，字段名与接口契约一致。
        return Map.of(
                // 指定要预约的工具 ID。
                "toolId", toolId,
                // 开始时间为两天后，结束时间为三天后，租期一天。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 结束时间比开始晚一天，满足最短租期要求。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人姓名用于订单展示，不影响档期判定。
                "pickupPerson", "王建国");
    }

    /**
     * 辅助方法：以唯一名称创建工具并审核通过。
     * 返回工具 ID，供后续预约与清理使用。
     * 名称使用统一前缀加后缀，既唯一又便于清理。
     * 创建后立即调用审核接口，把状态推进到 APPROVED。
     */
    private long createApprovedTool(String admin, String suffix) {
        // 创建工具：必填字段齐全，图片列表为空。
        JsonNode created = call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 名称带前缀与后缀，保证同一测试类内不重名。
                "name", PREFIX + suffix,
                // 型号、分类与损坏等级使用固定测试值。
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of()));
        // 从创建响应中取出工具 ID。
        long id = created.get("data").get("id").asLong();
        // 审核通过后工具才能进入可预约状态。
        JsonNode audit = call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                // 审核状态置为 APPROVED，完成上架前置条件。
                Map.of("auditStatus", "APPROVED"));
        // 断言审核接口返回成功，避免后续下单因状态不足失败。
        assertEquals("A200", audit.get("code").asString(), audit.toString());
        return id;
    }

    // 使用种子管理员账号登录，返回 Bearer 令牌。
    /** 辅助方法：用种子管理员账号登录并返回 Bearer 令牌。 */
    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 使用种子用户手机号密码登录，返回 Bearer 令牌。
    /** 辅助方法：用种子用户密码登录并返回 Bearer 令牌。 */
    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /**
     * 辅助方法：调用登录接口并断言业务码 A200，返回 tokenValue。
     * 登录失败时直接让测试失败，避免后续请求产生误导性断言。
     * 登录失败会直接断言失败，避免后续请求携带空令牌产生误导性结果。
     * 成功时返回 data.tokenValue，供请求头使用。
     */
    private String login(String url, Map<String, Object> body) {
        // 调用登录接口，令牌为空表示匿名请求。
        JsonNode response = call(HttpMethod.POST, url, null, body);
        // 先断言业务码 A200，确保登录成功。
        assertEquals("A200", response.get("code").asString(), response.toString());
        // 从响应中取出令牌字符串。
        return response.get("data").get("tokenValue").asString();
    }

    /**
     * 辅助方法：发送 JSON 请求并解析响应体。
     * 兼容空响应体，统一返回 JsonNode，便于直接断言 code/data 字段。
     * 统一处理请求头、JSON 序列化与响应解析。
     * 令牌为空时按匿名请求发送，用于验证 401。
     * 空响应体按空 JSON 对象处理，避免解析异常。
     */
    private JsonNode call(HttpMethod method, String url, String token, Object body) {
        // 构造请求方法并指定接口路径。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌时附加 Bearer 认证头。
        if (token != null) {
            // 令牌为空则不添加认证头，模拟未登录访问。
            spec.header("Authorization", "Bearer " + token);
        }
        // 有请求体时按 JSON 发送并设置 Content-Type。
        if (body != null) {
            // 请求体为空时保持无内容，适用于 GET 请求。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 执行请求并同步读取响应体。
        return spec.exchange((request, response) -> {
            // try-with-resources 确保响应流关闭。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取全部响应字节。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 空响应体转为空对象，便于统一断言 code 字段。
                return om.readTree(raw.isBlank() ? "{}" : raw);
            // IOException 转为非受检异常，保持调用方代码简洁。
            } catch (IOException e) {
                // 包装异常并保留原因，便于定位网络或解析问题。
                throw new IllegalStateException(e);
            }
        // 返回解析后的 JSON 树，供调用方断言字段。
        });
    }
}
