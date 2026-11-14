package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 测试设计说明
 * ------------
 * 一、被测能力：
 *     可用性接口根据工具状态、审核状态与租赁占用，
 *     计算查询窗口内的空闲时段，并在不可预约时给出原因。
 *
 * 二、占用模型：
 *     只有仍会占用工具的租赁状态参与计算。
 *     RESERVED、PICKED_UP、OVERDUE 等状态按 [startTime, endTime)
 *     占用档期；CANCELLED、RETURNED 等终态不占用。
 *     待支付订单在超时前占用，超时后释放。
 *
 * 三、为什么要直接插入租赁单：
 *     可用性关注时间区间切割，不关注下单与支付链路。
 *     直接写库可以精确构造首尾相接、跨天、完全覆盖等边界。
 *
 * 四、为什么用数据库时钟判断过期：
 *     应用与 MySQL 可能处于不同时区。
 *     通过 TIMESTAMPADD 与 NOW() 修改创建时间，
 *     可以避免测试机与数据库时钟差异造成的误判。
 *
 * 五、关键不变量：
 *     1. 相邻区间首尾相接不产生额外占用；
 *     2. 连续占用合并后再切割空闲窗口；
 *     3. 跨天计算保留日期边界；
 *     4. 未过期待支付占用，过期、取消、归还后释放；
 *     5. 建议窗口满足请求时长且不早于期望开始；
 *     6. 维修中或未审核工具返回可读的不可预约原因。
 *
 * 六、数据清理：
 *     租赁单与工具 ID 都登记到列表，AfterEach 统一删除，
 *     工具日志先删以满足外键与残留约束。
 *
 * 七、断言策略：
 *     同时断言 available 布尔值、freeWindows 数量与边界时间，
 *     只断言 available 会漏掉区间切割错误，
 *     只断言窗口时间会漏掉整体可预约性判断错误。
 */
/**
 * 工具可预约时段集成测试。
 * <p>
 * 测试范围：档期首尾相接、连续占用合并、跨天占用、待支付过期释放、
 * 取消/归还释放、建议窗口时长，以及工具不可租时的原因提示。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子账号。
 * 关键不变量：相邻租赁不阻塞新区间；占用区间合并后切割空闲窗口；
 * 建议窗口满足请求时长且不早于期望开始；未过期待支付占用档期，
 * 过期、取消与归还后释放；维修中或未审核工具必须返回不可预约原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AvailabilityIntegrationTest extends AbstractIntegrationTest {

    // 统一的时间序列化格式，与接口期望的 LocalDateTime 入参一致。
    // 固定到秒可以消除纳秒差异造成的字符串比较噪声。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 测试工具名称前缀，用于识别和清理本类创建的工具。
    private static final String TOOL_PREFIX = "IT-AVAIL-";
    // 自增序号用于生成唯一租赁单号，
    // 避免同一用例插入多笔租赁时撞唯一键。
    private static final AtomicInteger SEQ = new AtomicInteger();

    // 随机端口由 Spring Boot 启动时分配，
    // 测试客户端据此访问真实 HTTP 接口。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 租赁单 Mapper：直接布置占用区间，
    // 不经过下单、支付、取件流程，聚焦可用性计算本身。
    /** 租赁单 Mapper，用于布置占用区间。 */
    @Autowired
    RentalMapper rentalMapper;
    // 工具 Mapper：创建测试工具并在清理阶段删除。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：删除工具前先清理关联日志，
    // 避免外键或残留数据导致删除失败。
    /** 工具日志 Mapper，清理工具前先删日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;
    // 直接执行 SQL 调整创建时间，
    // 用于模拟“待支付已超时”这一时间相关场景。
    /** 直接执行 SQL 调整创建时间，模拟待支付过期。 */
    @Autowired
    JdbcTemplate jdbcTemplate;

    // HTTP 客户端在 @BeforeEach 中按随机端口创建。
    RestClient client;
    // JSON 解析器与客户端配合，把响应体转换为可断言的树模型。
    final ObjectMapper om = new ObjectMapper();
    // 本用例创建的租赁单 ID，AfterEach 统一删除，防止数据残留。
    /** 本用例创建的租赁单 ID，统一清理。 */
    final List<Long> rentalIds = new ArrayList<>();
    // 本用例创建的工具 ID，AfterEach 先删日志再删工具。
    /** 本用例创建的工具 ID，统一清理。 */
    final List<Long> toolIds = new ArrayList<>();

    // 响应封装同时保留 HTTP 状态与 JSON 业务体，
    // 便于同时断言协议层与业务层结果。
    record ApiResponse(int status, JsonNode body) {
    }

    // 每个用例前重建客户端，保证端口与本次应用实例一致。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // 用例结束后按记录顺序清理租赁单，再清理工具日志与工具。
    // 先删租赁再删工具，避免工具仍被业务数据引用。
    /**
     * 用例结束后删除本类插入的租赁单与工具日志/工具。
     * 先删租赁再删工具，并把 ID 列表清空。
     */
    @AfterEach
    // 清理本类插入的租赁单，防止占用区间影响后续用例。
    void cleanUp() {
        // 逐个删除租赁单，ID 来自 insertRental 的登记。
        for (Long id : rentalIds) {
            rentalMapper.deleteById(id);
        }
        // 清理工具前先删除工具日志，满足数据依赖顺序。
        for (Long id : toolIds) {
            // 删除工具日志。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, id));
            // 删除工具本体。
            toolMapper.deleteById(id);
        }
        // 清空 ID 列表，避免下一个用例误删上一轮记录。
        rentalIds.clear();
        toolIds.clear();
    }

    // 覆盖点：租赁恰好在查询窗口开始时结束。
    // 业务背景：占用区间按左闭右开处理，
    // 上一笔在 10:00 结束、下一笔从 10:00 开始不应互相阻塞。
    // 前置条件：占用 [start-2h, start)，查询 [start, start+2h)。
    // 验证路径：创建已审核工具 -> 插入昨天结束的租赁 ->
    // 调用可用性接口。
    // 断言目标：available=true；freeWindows 只有一段且覆盖整个窗口；
    // suggestionWindows 为空。
    // 失败后果：相邻档期被误判为冲突，工具可租时间被无谓压缩。
    // 该用例是区间边界处理的最小回归。
    /**
     * 场景：已有租赁恰好在查询窗口开始时结束。
     * 前置条件：占用区间为 [start-2h, start)，查询 [start, start+2h)。
     * 断言目标：窗口可预约；空闲窗口恰好覆盖整个查询区间；
     * 无需建议替代窗口，证明首尾相接不产生额外占用。
     */
    @Test
    // 用种子管理员创建并审核工具。
    void adjacent_rental_ending_at_window_start_does_not_block() {
        // 管理员登录，用于创建工具。
        String admin = adminLogin();
        // 种子用户登录，用于查询可用性。
        String user = userLogin();
        // 创建一台已审核通过的工具。
        long toolId = createApprovedTool(admin, "adjacent");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 占用区间在查询开始前结束，与查询窗口首尾相接。
        insertRental(toolId, "RESERVED", start.minusHours(2), start);

        // 调用可用性接口，返回 data 节点。
        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        // 首尾相接不应阻塞查询窗口。
        assertTrue(data.get("available").asBoolean(), data.toString());
        // 整个查询窗口应作为一个空闲段返回。
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        // 空闲段起点必须等于查询起点。
        assertTime(start, data.get("freeWindows").get(0), "startTime");
        // 空闲段终点必须等于查询终点。
        assertTime(start.plusHours(2), data.get("freeWindows").get(0), "endTime");
        // 窗口完全空闲时不需要建议替代时段。
        assertEquals(0, data.get("suggestionWindows").size(), data.toString());
    }

    // 覆盖点：连续租赁合并后的空闲窗口切割。
    // 业务背景：两笔租赁首尾相接时，占用应合并为一段，
    // 不能因为逐条计算而在中间留下虚假缝隙。
    // 前置条件：第一笔 RESERVED、第二笔 PICKED_UP，
    // 两段分别为 [start, start+1h) 与 [start+1h, start+2h)。
    // 验证路径：插入两笔租赁 -> 查询 [start, start+3h)。
    // 断言目标：available=false；空闲窗口只剩 [start+2h, start+3h)。
    // 失败后果：系统可能把已被占用的时段误判为空闲并允许下单。
    // 两笔租赁状态不同，也覆盖多状态合并逻辑。
    /**
     * 场景：两笔连续租赁覆盖查询窗口的前两个小时。
     * 前置条件：第一笔已预订、第二笔已取件，两段首尾相接。
     * 断言目标：窗口整体不可预约；空闲窗口只剩后一小时，
     * 说明相邻占用被合并而不是产生拼接缝隙。
     */
    @Test
    // 管理员登录，用于创建工具。
    void merged_rentals_split_free_windows() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "merged");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 第一段占用：已预订，10:00-11:00。
        insertRental(toolId, "RESERVED", start, start.plusHours(1));
        // 第二段占用：已取件，11:00-12:00，与第一段首尾相接。
        insertRental(toolId, "PICKED_UP", start.plusHours(1), start.plusHours(2));

        // 查询三小时窗口，前两小时被占用。
        JsonNode data = availability(user, toolId, start, start.plusHours(3));

        // 窗口整体不可预约。
        assertFalse(data.get("available").asBoolean(), data.toString());
        // 合并后只应剩余一段空闲窗口。
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        // 空闲窗口从 12:00 开始。
        assertTime(start.plusHours(2), data.get("freeWindows").get(0), "startTime");
        // 空闲窗口到 13:00 结束。
        assertTime(start.plusHours(3), data.get("freeWindows").get(0), "endTime");
    }

    // 覆盖点：完全被占用的窗口如何给出建议时段。
    // 业务背景：用户期望的时间不可用时，
    // 系统应给出满足同样时长、且不早于期望开始的可选窗口。
    // 前置条件：占用从查询前一小时持续到查询后三小时。
    // 验证路径：插入长租赁 -> 查询两小时窗口 -> 遍历建议列表。
    // 断言目标：available=false 且 freeWindows 为空；
    // 每个建议窗口时长等于请求时长；
    // 每个建议不早于期望开始；
    // 最近建议紧接占用结束时间。
    // 失败后果：用户只能看到“不可预约”，无法自助改期。
    // 建议时段过多或过晚都会降低转化率。
    /**
     * 场景：查询窗口被一笔长租赁完全覆盖。
     * 前置条件：占用从查询前一小时持续到查询后三小时。
     * 断言目标：available=false 且无空闲窗口；建议窗口非空；
     * 每个建议窗口时长等于请求时长、不早于期望开始，
     * 最近建议紧接占用结束时间，方便用户改期。
     */
    @Test
    // 管理员登录。
    void fully_occupied_window_suggests_slots_of_requested_duration() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "full");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 占用覆盖查询前后共六小时。
        insertRental(toolId, "RESERVED", start.minusHours(1), start.plusHours(5));

        // 查询两小时窗口，完全落在占用区间内。
        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        // 查询窗口不可预约。
        assertFalse(data.get("available").asBoolean(), data.toString());
        // 没有空闲窗口。
        assertEquals(0, data.get("freeWindows").size(), data.toString());
        // 读取建议窗口列表。
        JsonNode suggestions = data.get("suggestionWindows");
        // 建议列表不能为空，否则用户没有可选替代。
        assertFalse(suggestions.isEmpty(), data.toString());
        // 请求时长为两小时。
        Duration requested = Duration.ofHours(2);
        // 逐个检查建议窗口的时长与下界。
        for (JsonNode slot : suggestions) {
            // 解析建议窗口起点。
            LocalDateTime slotStart = time(slot, "startTime");
            // 解析建议窗口终点。
            LocalDateTime slotEnd = time(slot, "endTime");
            // 建议窗口必须恰好满足所需时长。
            assertEquals(requested, Duration.between(slotStart, slotEnd), "建议窗口必须满足所需时长");
            // 建议窗口不得早于用户期望的开始时间。
            assertFalse(slotStart.isBefore(start), "建议窗口不得早于期望开始时间");
        }
        // 最近的可选窗口应紧接占用结束时间，而不是额外加一小时缓冲。
        // 最近建议从占用结束（15:00）开始，而不是占用结束 +1 小时
        // 最近建议应从占用结束处开始，
        // 而不是额外增加一小时缓冲。
        assertTime(start.plusHours(5), suggestions.get(0), "startTime");
    }

    // 覆盖点：跨午夜的时间区间计算。
    // 业务背景：工具可以跨天租借，
    // 占用与查询都可能跨越日期边界，不能只比较时分。
    // 前置条件：占用 22:00 到次日 01:00，查询 23:00 到次日 03:00。
    // 验证路径：插入跨天租赁 -> 查询跨天窗口。
    // 断言目标：available=false；
    // 空闲窗口为次日 01:00 到 03:00。
    // 失败后果：跨天场景出现错误的空闲段或负时长。
    // 该用例同时验证日期边界与左闭右开语义。
    /**
     * 场景：占用跨越午夜（22:00 到次日 01:00），查询 23:00 到次日 03:00。
     * 前置条件：占用与查询区间部分重叠。
     * 断言目标：窗口不可预约；空闲窗口为次日 01:00 到 03:00，
     * 验证跨天时间计算不丢失日期边界。
     */
    @Test
    // 管理员登录。
    void cross_day_occupancy_leaves_free_window_across_midnight() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "cross-day");
        // 查询从明天 20:00 开始。
        LocalDateTime start = tomorrowAt(20);
        // 占用 22:00 - 次日 01:00
        // 占用 22:00 到次日 01:00。
        insertRental(toolId, "OVERDUE", start.plusHours(2), start.plusHours(5));

        // 期望 23:00 - 次日 03:00
        // 查询 23:00 到次日 03:00，与占用部分重叠。
        JsonNode data = availability(user, toolId, start.plusHours(3), start.plusHours(7));

        // 窗口不可预约。
        assertFalse(data.get("available").asBoolean(), data.toString());
        // 只剩一段空闲窗口。
        assertEquals(1, data.get("freeWindows").size(), data.toString());
        // 空闲窗口从次日 01:00 开始。
        assertTime(start.plusHours(5), data.get("freeWindows").get(0), "startTime");
        // 空闲窗口到次日 03:00 结束。
        assertTime(start.plusHours(7), data.get("freeWindows").get(0), "endTime");
    }

    // 覆盖点：未过期待支付订单仍占用档期。
    // 业务背景：用户提交订单后尚未支付，
    // 系统需要短暂锁定档期，防止同一时段被重复抢占。
    // 前置条件：创建时间在 30 分钟超时窗口内。
    // 验证路径：插入 PENDING_PAYMENT 租赁 -> 查询对应窗口。
    // 断言目标：available=false。
    // 失败后果：多个用户同时支付成功，造成超卖。
    // 该用例与“过期释放”用例构成时间边界的两端。
    /**
     * 场景：存在未过期的待支付租赁单。
     * 前置条件：创建时间在超时窗口内。
     * 断言目标：窗口不可预约，待支付订单在超时前仍占用档期，
     * 防止同一档期被重复抢占。
     */
    @Test
    // 管理员登录。
    void non_expired_pending_payment_blocks_window() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "pending-active");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 插入一笔刚创建的待支付订单，尚未超时。
        insertRental(toolId, "PENDING_PAYMENT", start, start.plusHours(2));

        // 查询被该订单占用的窗口。
        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        // 未过期待支付必须继续占用档期。
        assertFalse(data.get("available").asBoolean(), data.toString());
    }

    // 覆盖点：待支付订单超时后释放档期。
    // 业务背景：超过支付时限的订单不再具备履约可能，
     // 继续占用会阻止其他用户下单。
    // 前置条件：待支付超时为 30 分钟。
    // 验证路径：插入待支付订单 -> 用数据库时钟把创建时间改为 31 分钟前 ->
    // 查询同一窗口。
    // 断言目标：available=true。
    // 失败后果：僵尸订单长期锁死热门档期。
    // 使用数据库 NOW() 比较，避免应用与 MySQL 时区差异造成误判。
    /**
     * 场景：待支付租赁单创建时间被改到 31 分钟前。
     * 前置条件：待支付超时为 30 分钟，且使用数据库 NOW() 比较避免时区偏差。
     * 断言目标：窗口恢复可预约，过期订单不再占用档期。
     */
    @Test
    // 管理员登录。
    void expired_pending_payment_releases_window() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "pending-expired");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 插入一笔待支付订单并记录 ID，便于后续调整创建时间。
        long rentalId = insertRental(toolId, "PENDING_PAYMENT", start, start.plusHours(2));
        // 待支付是否过期以数据库时钟为准，避免应用与 MySQL 时区差造成误判。
        // 与数据库时钟比较，避免应用与 MySQL 容器时区不一致
        // 把创建时间改到 31 分钟前，超过 30 分钟超时阈值。
        // 使用数据库时钟计算，保证与业务判断使用同一时间基准。
        jdbcTemplate.update(
                "UPDATE t_rental SET create_time = TIMESTAMPADD(MINUTE, -31, NOW()) WHERE id = ?", rentalId);

        // 再次查询原本被占用的窗口。
        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        // 过期订单不再占用，窗口恢复可预约。
        assertTrue(data.get("available").asBoolean(), data.toString());
    }

    // 覆盖点：终态租赁不占用档期。
    // 业务背景：已取消和已归还的租赁不再拥有工具使用权，
     // 继续占用会导致工具可租时间被永久压缩。
    // 前置条件：两笔租赁时间相同但状态分别为 CANCELLED、RETURNED。
    // 验证路径：插入两笔终态租赁 -> 查询对应窗口。
    // 断言目标：available=true。
    // 失败后果：历史订单越多，工具显示可租时间越少。
    // 该用例与活动状态用例形成对照。
    /**
     * 场景：同一档期存在已取消与已归还的租赁单。
     * 前置条件：两者都不再占用工具。
     * 断言目标：窗口可预约，终态租赁不影响可用性计算。
     */
    @Test
    // 管理员登录。
    void cancelled_and_returned_rentals_release_window() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "released");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);
        // 已取消租赁，不再占用工具。
        insertRental(toolId, "CANCELLED", start, start.plusHours(2));
        // 已归还租赁，同样不再占用工具。
        insertRental(toolId, "RETURNED", start, start.plusHours(2));

        // 查询同一时间窗口。
        JsonNode data = availability(user, toolId, start, start.plusHours(2));

        // 终态租赁不应阻塞预约。
        assertTrue(data.get("available").asBoolean(), data.toString());
    }

    // 覆盖点：不可租工具的原因提示。
    // 业务背景：工具可能因维修中或尚未审核而不可预约。
    //     前端不能只灰置按钮，还要能展示具体原因。
    // 前置条件：一台工具被置为 MAINTENANCE，
     // 另一台工具保持待审核状态。
    // 验证路径：分别查询两台工具的可用性。
    // 断言目标：两者 available=false；
     // message 分别包含“不可预约”与“审核”。
    // 失败后果：用户不知道为何不能租，客服也无法解释。
    // 原因文案属于接口契约的一部分。
    /**
     * 场景：分别查询维修中工具与未审核工具的可用性。
     * 前置条件：工具状态或审核状态不满足出借条件。
     * 断言目标：两者均返回不可预约，并给出可读原因
     * （“不可预约”与“审核”），让前端能直接展示原因而非仅灰置。
     */
    @Test
    // 管理员登录。
    void unrentable_tool_returns_reason() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "unavailable");
        // 查询窗口从明天 10:00 开始。
        LocalDateTime start = tomorrowAt(10);

        // 把工具状态改为维修中，模拟暂不可租。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status", admin,
                Map.of("status", "MAINTENANCE")).status());
        // 查询维修中工具的可用性。
        JsonNode maintenance = availability(user, toolId, start, start.plusHours(2));
        // 维修中工具不可预约。
        assertFalse(maintenance.get("available").asBoolean(), maintenance.toString());
        // 原因文案应包含“不可预约”，供前端直接展示。
        assertTrue(maintenance.get("message").asString().contains("不可预约"), maintenance.toString());

        // 再创建一台未审核工具，作为第二种不可租原因。
        long pendingAuditId = createTool(admin, "pending-audit");
        // 查询未审核工具的可用性。
        JsonNode pendingAudit = availability(user, pendingAuditId, start, start.plusHours(2));
        // 未审核工具同样不可预约。
        assertFalse(pendingAudit.get("available").asBoolean(), pendingAudit.toString());
        // 原因文案应包含“审核”，与维修中场景区分。
        assertTrue(pendingAudit.get("message").asString().contains("审核"), pendingAudit.toString());
    }

    // 辅助方法：调用可用性接口并返回 data 节点。
    // 时间按统一格式拼接，避免时区与精度差异。
    // 只接受成功响应，失败会由 ok 抛出断言错误。
    /**
     * 辅助方法：调用可用性接口并返回 data 节点。
     * 时间按统一格式拼接，避免时区与精度差异。
     */
    // 拼接查询 URL，工具 ID 与起止时间都来自用例入参。
    private JsonNode availability(String token, long toolId, LocalDateTime start, LocalDateTime end) {
        String url = "/api/user/tools/" + toolId + "/availability"
                + "?startTime=" + start.format(FORMAT) + "&endTime=" + end.format(FORMAT);
        return ok(call(HttpMethod.GET, url, token, null));
    }

    // 辅助方法：直接插入指定状态与时间窗口的租赁单。
    // 待支付状态同步押金为 UNPAID，其他状态为 PAID；
    // 单号带自增序号并登记到清理列表。
    /**
     * 辅助方法：直接插入指定状态与时间窗口的租赁单。
     * 待支付状态同步押金为 UNPAID，其他状态为 PAID；
     * 单号带自增序号并登记到清理列表。
     */
    // 构造租赁实体，只填写可用性计算与数据库约束必需的字段。
    private long insertRental(long toolId, String status, LocalDateTime start, LocalDateTime end) {
        // 唯一单号，避免多笔租赁撞唯一键。
        Rental rental = new Rental();
        // 关联测试工具。
        rental.setOrderNo("IT-AVAIL-" + SEQ.incrementAndGet());
        // 工具编码与名称仅用于展示，不影响可用性计算。
        rental.setToolId(toolId);
        // 使用种子用户 1，满足用户外键约束。
        rental.setToolCode("IT-AVAIL-CODE");
        // 租借人信息按种子数据填写。
        rental.setToolName("可用性测试工具");
        // 手机号与种子用户保持一致。
        rental.setUserId(1L);
        // 身份证号使用脱敏格式，避免测试数据包含真实证件。
        rental.setRenterName("王建国");
        // 占用开始时间。
        rental.setRenterPhone("13800000001");
        // 占用结束时间，与开始时间共同构成左闭右开区间。
        rental.setRenterIdCardMasked("3101********1234");
        // 取件人。
        rental.setStartTime(start);
        // 押金金额固定，便于断言与清理。
        rental.setEndTime(end);
        // 待支付订单押金未付，其他状态视为已付。
        rental.setPickupPerson("王建国");
        // 租赁状态决定是否占用档期。
        rental.setDepositAmount(new BigDecimal("100.00"));
        // 逾期费初始为 0，避免影响可用性判断。
        rental.setDepositStatus("PENDING_PAYMENT".equals(status) ? "UNPAID" : "PAID");
        // 损坏费初始为 0。
        rental.setStatus(status);
        // 退款金额初始为 0。
        rental.setOverdueFee(BigDecimal.ZERO);
        rental.setDamageFee(BigDecimal.ZERO);
        rental.setRefundAmount(BigDecimal.ZERO);
        // 落库后取得自增 ID。
        rentalMapper.insert(rental);
        // 登记 ID，供 AfterEach 统一清理。
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    // 辅助方法：创建工具并审核通过。
    // 可用性接口只对审核通过的工具返回可预约结果，
    // 因此大多数用例都需要先走审核。
    /** 辅助方法：创建工具并审核通过，返回工具 ID。 */
    // 先创建工具，再调用审核接口。
    private long createApprovedTool(String admin, String suffix) {
        long id = createTool(admin, suffix);
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    // 辅助方法：以统一前缀创建工具并登记到清理列表。
    // 请求体只包含创建工具所需的最小字段。
    /** 辅助方法：以统一前缀创建工具并登记到清理列表。 */
    // 工具名称带统一前缀，清理时按前缀定位。
    private long createTool(String admin, String suffix) {
        // 记录名称，便于失败信息定位。
        String name = TOOL_PREFIX + suffix;
        // 调用管理端创建工具接口。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", name,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", new BigDecimal("100"),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        // 取出新工具 ID。
        long id = created.get("id").asLong();
        // 登记 ID，供清理阶段删除。
        toolIds.add(id);
        return id;
    }

    // 辅助方法：返回明天指定整点时间。
    // 消除分、秒与纳秒噪声，避免时间比较出现意外差异。
    /** 辅助方法：返回明天指定整点时间，消除秒与纳秒噪声。 */
    // 基于当前时间加一天，再归整到指定小时。
    private LocalDateTime tomorrowAt(int hour) {
        return LocalDateTime.now().plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    // 辅助方法：从响应节点解析 ISO 本地时间字段。
    /** 辅助方法：从响应节点解析 ISO 本地时间字段。 */
    private LocalDateTime time(JsonNode node, String field) {
        return LocalDateTime.parse(node.get(field).asString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // 辅助方法：断言响应中的时间字段等于期望值。
    // 失败信息带上整个节点，便于定位边界计算偏差。
    /** 辅助方法：断言响应中的时间字段等于期望值。 */
    private void assertTime(LocalDateTime expected, JsonNode node, String field) {
        assertEquals(expected, time(node, field), node.toString());
    }

    // 辅助方法：断言 HTTP 200 与业务码 A200，并返回 data 节点。
    /** 辅助方法：断言 HTTP 200 与 A200，并返回 data 节点。 */
    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body().get("data");
    }

    // 辅助方法：种子管理员登录并返回令牌。
    // 管理员令牌用于创建工具与审核工具。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 辅助方法：种子用户密码登录并返回令牌。
    // 用户令牌用于调用可用性查询接口。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    // 辅助方法：调用登录接口，断言成功并取出 tokenValue。
    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    private String login(String url, Map<String, Object> body) {
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。
    // 所有用例通过该方法发起请求，保证行为一致。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // 按 HTTP 方法构建请求。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 创建请求规格，后续按需追加头与请求体。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 只有需要登录的接口才添加 Bearer 头；
        // 匿名场景通过 token=null 复用该方法。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 无请求体的请求不设置 Content-Type，避免服务端误判。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取 4xx/5xx 响应体，
        // 因此失败场景也能被用例断言。
        return spec.exchange((request, response) -> {
            // 读取完整响应流。
            try (InputStream in = response.getBody()) {
                // 转成 UTF-8 字符串。
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
