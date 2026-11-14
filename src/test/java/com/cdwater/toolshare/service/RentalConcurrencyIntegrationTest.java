package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.rental.RentalService;
import com.cdwater.toolshare.module.tool.Tool;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租赁单并发与状态机集成测试。
 * <p>
 * 测试范围：同一档期的并发预约、支付与取消竞争、非法状态跃迁、
 * 取件前置条件以及逾期状态物化与通知去重。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储；
 * 并发用例使用双线程栅栏同时发起请求。
 * 关键不变量：同一档期只能成功一单；支付与取消并发后押金状态必须自洽；
 * 非法跃迁统一 409；取件要求押金已付且工具可用；
 * 逾期扫描可重复执行但只通知一次。
 * 覆盖接口：POST /api/user/rentals（预约）、/pay、/cancel、
 * POST /api/admin/rentals/{id}/pickup、/return，以及工具状态与逾期扫描。
 * 并发用例通过 CountDownLatch 栅栏让两个请求同时进入业务逻辑；
 * 断言不仅看响应码，还回查数据库状态与押金字段，防止“响应成功但数据不一致”。
 * 并发策略：两个请求共享同一份请求体与同一档期，只让调度顺序变化；
 * 超时时间足以覆盖本地数据库与线程调度，避免偶发超时造成误报。
 * 所有回查都在请求完成后执行，保证读到最终提交状态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // ISO 本地时间格式与接口约定的入参格式一致，秒级精度避免纳秒差异。
    // 所有请求时间都经 format 转换，避免 JVM 默认格式差异。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 工具名前缀用于清理本类创建的工具及其级联数据。
    // 前缀只用于测试数据识别与清理，不参与业务逻辑。
    private static final String PREFIX = "IT-RENTAL-";

    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    @Autowired
    // 工具 Mapper 同时负责创建、审核与最终清理。
    // 字段注释保留原有 Javadoc，补充 Mapper 的职责边界。
    /** 工具 Mapper，用于创建与清理工具。 */
    // Mapper 由 Spring 注入，测试方法直接调用其 CRUD。
    ToolMapper toolMapper;
    @Autowired
    // 清理工具前必须先删日志，否则外键约束会阻止删除。
    // 日志表在工具删除前必须清空，否则外键约束会报错。
    /** 工具日志 Mapper，清理工具前先删关联日志。 */
    // 日志用于记录工具状态迁移，测试不校验其内容。
    ToolLogMapper toolLogMapper;
    @Autowired
    // 租赁 Mapper 用于回查最终状态、押金与退款额。
    // 租赁表是断言最终状态的核心表。
    /** 租赁单 Mapper，用于构造状态与断言最终结果。 */
    // 通过主键读取实体，比再次走接口更直接。
    RentalMapper rentalMapper;
    @Autowired
    // 报修记录可能引用工具，删除工具前需要级联清理。
    // 报修表可能引用工具，清理顺序必须先于工具。
    /** 报修 Mapper，清理工具时级联删除报修记录。 */
    // 测试不创建报修，只在清理时防御性删除。
    DamageReportMapper damageReportMapper;
    @Autowired
    // 消息 Mapper 用于验证逾期通知只落一条。
    // 消息表用于校验逾期通知的唯一性。
    /** 消息 Mapper，用于验证逾期通知只写一条。 */
    // 按业务键统计，不依赖消息标题文案。
    MessageMapper messageMapper;
    @Autowired
    // 直接调用扫描方法，跳过定时任务调度，测试结果确定。
    // 扫描方法由定时任务调用，测试直接触发以获得确定性。
    /** 直接调用逾期扫描，不依赖定时任务调度。 */
    // 扫描是幂等的，这一假设由逾期用例验证。
    RentalService rentalService;

    // 每个用例按随机端口重建客户端，避免连接复用带来的状态残留。
    RestClient client;
    // ObjectMapper 只读响应，不参与请求序列化。
    // 响应统一解析为 JsonNode，便于读取业务码与错误消息。
    // 每个客户端实例独立，不共享连接池。
    final ObjectMapper om = new ObjectMapper();

    // 同时保留状态码与 JSON，错误场景可以打印完整上下文。
    record ApiResponse(int status, JsonNode body) {
    }

    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // 随机端口由 Spring 分配，客户端必须指向它才能访问被测应用。
        // 构建后不可变，后续请求只读使用。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按名称前缀清理工具及其报修、租赁与日志。
     * 先删子表再删工具，避免留下悬挂数据。
     * 清理按工具前缀枚举，先删所有引用表再删主键。
     * 若清理顺序错误，外键约束会让 AfterEach 直接失败并暴露问题。
     */
    // 只清理名称带固定前缀的工具，不触碰种子工具与其他用例数据。
    @AfterEach
    void cleanUp() {
        // 列表查询只返回本类前缀的工具，范围可控。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                // likeRight 生成 name LIKE 'IT-RENTAL-%'。
                .likeRight(Tool::getName, PREFIX));
        // 按规定顺序清理子表：报修、租赁、日志都引用工具主键。
        for (Tool tool : tools) {
            // 工具的每一个引用表都必须在删除前清空。
            // 报修表记录工具 ID，先删除避免外键阻断。
            // 报修按 toolId 等值删除，覆盖该工具的全部记录。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 租赁表同样引用工具，且可能被并发用例写入。
            // 租赁单可能由并发用例创建，删除时按工具过滤。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 工具日志是审计数据，最后清理由工具删除前的最后一步完成。
            // 工具日志是审计表，最后清理。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 子表清空后再删除工具本身。
            // 所有子表清理完毕后才允许删除工具主键。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：两个线程在同一时刻为同一工具、同一档期提交预约。
     * 前置条件：工具已审核通过且档期空闲；请求通过栅栏同时放行。
     * 断言目标：恰好一单成功、一单返回 C409，数据库只存在一条租赁单，
     * 证明档期占用具备并发互斥。
     * 契约补充：档期冲突由服务端在事务内判定，返回 C409；
     * 两个请求使用完全相同的请求体，模拟同一用户重复提交或两端同时点击。
     * 数据库最终只允许一条租赁单，响应层与存储层必须同时满足。
     * 观察点：成功数、冲突数与数据库计数三者必须同时为 1/1/1；
     * 任意一项不符都说明互斥发生在不同层或只做了应用层去重。
     * 该用例不依赖请求先后顺序，两个线程的角色完全对称。
     */
    @Test
    void concurrent_booking_for_same_slot_only_one_succeeds() throws Exception {
        // 管理员用于创建并审核工具，用户用于发起预约。
        // 管理员令牌用于创建与审核工具。
        String admin = adminLogin();
        // 工具审核通过后才允许预约，否则会在更早的前置校验失败。
        // 用户令牌用于发起预约请求。
        String user = userLogin();
        // 请求体固定同一工具与同一未来档期。
        // 工具创建后立即审核通过，避免状态成为干扰变量。
        long toolId = createApprovedTool(admin, "concurrent");
        // 同一请求体被两个线程复用，差异只在执行时机。
        // 同一请求体被两个线程复用，确保竞争的是同一档期。
        Map<String, Object> booking = booking(toolId);

        // 两个预约请求通过栅栏同时发出，模拟真实抢订竞争。
        // 两个 Supplier 分别代表两个并发请求。
        // concurrently 内部用栅栏同步，返回值顺序与入参一致。
        // 两个请求同时放行，尽量制造数据库层的竞争窗口。
        // 两个请求都指向 /api/user/rentals。
        // 第二个请求与第一个完全相同。
        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/rentals", user, booking),
                () -> call(HttpMethod.POST, "/api/user/rentals", user, booking));

        // 统计成功响应数量，必须恰好为 1。
        // 成功数必须为 1，否则档期互斥失效。
        long success = results.stream().filter(r -> "A200".equals(code(r))).count();
        // 统计冲突响应数量，必须恰好为 1。
        // 冲突数必须为 1，且不能出现其他业务码。
        long conflict = results.stream().filter(r -> "C409".equals(code(r))).count();
        // 两个请求不能都成功，也不能都失败。
        // results.toString 在失败时输出两个响应，便于定位。
        assertEquals(1, success, results.toString());
        // C409 是业务层对档期冲突的统一表达。
        // 若为 2/0，说明并发控制缺失；若为 0/2，说明误判冲突。
        assertEquals(1, conflict, results.toString());
        // 回查数据库，确认最终只落一条租赁单。
        // 直接查库验证持久层结果，不信任响应体。
        // 计数按工具 ID 过滤，不受其他用例数据影响。
        // 工具 ID 是唯一的过滤条件。
        assertEquals(1, rentalMapper.selectCount(new LambdaQueryWrapper<Rental>()
                .eq(Rental::getToolId, toolId)));
    }

    /** 辅助方法：构造同一工具、同一未来档期的预约请求体。 */
    @Test
    void unexpired_pending_payment_blocks_booking() {
        // 管理员创建并审核工具，用户准备预约。
        // 管理员负责创建与审核工具。
        String admin = adminLogin();
        // 首单使用默认待支付超时配置，测试期间不会过期。
        // 用户负责发起两次预约。
        String user = userLogin();
        // 两个请求使用同一档期与同一用户。
        // 工具与档期与并发用例一致，只是请求串行。
        long toolId = createApprovedTool(admin, "pending-block");
        // 首单与第二单共用同一个请求体。
        Map<String, Object> booking = booking(toolId);

        // 首单必须成功，否则第二单的冲突断言没有意义。
        // 首单成功后档期被占用，状态为待支付。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals", user, booking).status());
        // 第二单与首单完全相同，仅时间上稍后。
        // 第二单不带任何变化，仍应被拒绝。
        ApiResponse second = call(HttpMethod.POST, "/api/user/rentals", user, booking);
        // 冲突返回 C409，而不是创建第二张待支付订单。
        // 冲突码 C409 表示档期被未过期订单占用。
        assertEquals("C409", code(second), second.body().toString());
    }

    /**
     * 场景：同一租赁单同时发起支付与取消。
     * 前置条件：订单处于待支付状态，取消必然成功。
     * 断言目标：最终状态为 CANCELLED；若支付先成功则押金标记 REFUNDED
     * 且退款额等于押金，否则支付返回 C409 且押金保持 UNPAID，
     * 不允许出现“已取消但仍占用押金”的中间态。
     * 契约补充：支付与取消并发时，最终状态必须是 CANCELLED；
     * 若支付先成功，则取消必须同步退款；若取消先成功，则支付必须失败。
     * 两种合法结果都不允许出现“已取消但押金仍被占用”的中间态。
     * 观察点：取消必然成功；支付只有成功或冲突两种结果。
     * 成功分支校验退款金额，冲突分支校验押金未动，二者覆盖全部可能。
     * 不允许“支付成功但押金未退”或“取消成功但押金仍为 PAID”。
     */
    @Test
    void concurrent_pay_and_cancel_keep_deposit_consistent() throws Exception {
        // 管理员创建工具，用户创建一张待支付租赁单。
        // 管理员用于创建并审核工具。
        String admin = adminLogin();
        // 租赁单初始状态为 PENDING_PAYMENT，押金为 UNPAID。
        // 用户用于创建待支付订单。
        String user = userLogin();
        // 工具审核通过后才可被预约。
        long toolId = createApprovedTool(admin, "pay-cancel");
        // 支付与取消同时发起，顺序由线程调度决定。
        // 新建订单状态为 PENDING_PAYMENT，押金为 UNPAID。
        long rentalId = createRental(user, toolId);

        // 第一个 Supplier 调用支付接口。
        // 支付与取消共享同一 rentalId。
        // 支付请求体为空 Map，接口无需额外参数。
        // 第二个 Supplier 调用取消接口，并附带取消原因。
        // 取消请求携带原因，业务上要求说明取消理由。
        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()),
                () -> call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/cancel", user,
                        Map.of("reason", "并发取消")));

        // 结果列表顺序与提交顺序一致，便于分别断言。
        // 结果顺序固定：索引 0 是支付，索引 1 是取消。
        ApiResponse pay = results.get(0);
        // 取消是必然成功的分支：待支付状态允许取消。
        // 取消结果单独取出，避免与支付混淆。
        ApiResponse cancel = results.get(1);
        // 取消响应必须是 200，否则并发模型不成立。
        // 待支付订单取消必然成功，是并发场景的稳定锚点。
        assertTrue(200 == cancel.status(), cancel.body().toString());

        // 回查数据库，最终状态是唯一事实来源。
        // 最终状态以数据库为准，而不是最后一个响应。
        Rental rental = rentalMapper.selectById(rentalId);
        // 无论竞争结果如何，订单都必须停在 CANCELLED。
        // 取消后订单不能再回到 BOOKED 或 PICKED_UP。
        assertEquals("CANCELLED", rental.getStatus());
        // 支付赢得竞争时，取消必须把押金全额退回。
        // 支付若赢得竞争，押金必须已全额退还；否则应明确冲突。
        // 支付响应 200 表示扣款成功，随后被取消覆盖。
        // 支付赢家分支：支付已成功扣款并被随后的取消退款。
        if (200 == pay.status()) {
            // 押金状态必须变为 REFUNDED。
            // 押金状态与订单状态必须一致。
            assertEquals("REFUNDED", rental.getDepositStatus());
            // 退款额等于押金，不能多退或少退。
            // compareTo 返回 0 表示金额完全相等。
            assertEquals(0, rental.getRefundAmount().compareTo(rental.getDepositAmount()));
        // 取消赢家分支：支付被状态机拒绝。
        } else {
            // 支付失败时，订单仍被取消，押金未被扣走。
            // 支付返回 C409，说明取消已先一步改变状态。
            // 支付返回 C409，说明订单已不可支付。
            assertEquals("C409", code(pay), pay.body().toString());
            // 押金保持 UNPAID，而不是 PAID 或 REFUNDED。
            // 押金从未被扣走，保持 UNPAID。
            assertEquals("UNPAID", rental.getDepositStatus());
            // 退款额为零，没有发生资金流动。
            // signum 为 0 表示退款额为零。
            assertEquals(0, rental.getRefundAmount().signum());
        }
    }

    /**
     * 场景：在未支付时取件、重复支付、取消后取件/归还。
     * 前置条件：状态机只允许支付->预订->取件->归还的合法路径。
     * 断言目标：所有越级或重复操作均返回 C409，且不会推进订单状态。
     * 契约补充：状态机只允许 PENDING_PAYMENT -> BOOKED -> PICKED_UP -> RETURNED；
     * 取消是 PENDING_PAYMENT/BOOKED 的旁路，取消后不可再取件或归还。
     * 所有非法跃迁统一返回 C409，避免前端按状态码分支爆炸。
     * 观察点：每个非法请求都返回 C409，且订单状态不因失败而改变。
     * 先支付再取消是合法旁路，用来构造终态，之后所有跃迁都必须失败。
     * 该用例串行执行，错误码语义比并发时序更重要。
     */
    @Test
    void illegal_transitions_return_409() {
        // 创建工具与待支付租赁单，后续所有操作都针对该单。
        // 管理员用于取件与归还操作。
        String admin = adminLogin();
        // 用户用于支付与取消操作。
        String user = userLogin();
        // 工具与订单准备完毕后开始状态跃迁测试。
        long toolId = createApprovedTool(admin, "state");
        // 管理端基础路径，取件与归还都挂在该前缀下。
        // 订单初始为待支付，是状态机的起点。
        long rentalId = createRental(user, toolId);
        // 管理端路径在订单创建后即可确定。
        String base = "/api/admin/rentals/" + rentalId;

        // 未支付直接取件：跳过支付步骤，属于非法跃迁。
        // 取件要求押金已付，未支付时返回冲突。
        assertCode("C409", call(HttpMethod.POST, base + "/pickup", admin, Map.of()));
        // 支付接口是合法路径的第一步，必须成功。
        // 支付是合法路径，必须成功。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 重复支付：订单已进入 BOOKED，不能再次扣款。
        // 重复支付应被状态机拒绝。
        assertCode("C409", call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()));
        // 支付后取消是允许的旁路，取消成功。
        // 支付后取消是允许的，用于制造终态。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/cancel",
                user, Map.of("reason", "行程变化")).status());
        // 取消后取件必须失败，状态不能倒退。
        // 终态订单不能再取件。
        assertCode("C409", call(HttpMethod.POST, base + "/pickup", admin, Map.of()));
        // 取消后归还同样失败，订单已终结。
        // 终态订单也不能归还。
        // 归还请求体需要实际归还时间与损坏结果。
        // 实际归还时间使用当前时间并去掉纳秒。
        // 损坏结果 INTACT 表示工具完好，减少费用分支干扰。
        // 归还接口只接受管理员令牌，用户自助归还不存在。
        assertCode("C409", call(HttpMethod.POST, base + "/return", admin,
                Map.of("actualReturnTime", LocalDateTime.now().withNano(0).format(FORMAT),
                        "damageResult", "INTACT")));
    }

    /**
     * 场景：先尝试在未支付时取件，再支付并把工具改为维修中后重试。
     * 前置条件：取件同时校验押金与工具可用状态。
     * 断言目标：两种失败均返回 C409，错误信息分别提示押金与工具不可用，
     * 防止未付款或维修中的工具被借出。
     * 契约补充：取件同时校验押金已付与工具处于 AVAILABLE；
     * 任一条件不满足都返回 C409，并在消息中说明具体原因。
     * 工具进入 MAINTENANCE 后即使订单已支付也不能借出。
     * 观察点：错误消息分别包含“押金”与“不可”，说明校验顺序稳定。
     * 支付成功只解除押金条件，工具状态仍可独立阻止取件。
     * 管理端修改工具状态后无需重新审核，即可参与取件校验。
     */
    @Test
    void pickup_requires_paid_deposit_and_available_tool() {
        // 创建工具并生成一张待支付租赁单。
        // 管理员用于取件，用户用于支付。
        String admin = adminLogin();
        String user = userLogin();
        // 工具审核通过并生成订单。
        long toolId = createApprovedTool(admin, "pickup-guard");
        // 订单初始为待支付，用于验证押金前置条件。
        long rentalId = createRental(user, toolId);
        // 取件接口固定在当前租赁单下。
        // 取件 URL 在订单创建后固定。
        String pickupUrl = "/api/admin/rentals/" + rentalId + "/pickup";

        // 第一次取件发生在支付前，应因押金未付失败。
        // 支付前取件：押金条件不满足。
        ApiResponse unpaid = call(HttpMethod.POST, pickupUrl, admin, Map.of());
        // 统一用 assertCode 校验业务码与 HTTP 状态。
        // 业务码与 HTTP 状态同时校验。
        assertCode("C409", unpaid);
        // 错误消息包含“押金”，便于前端直接提示用户。
        // 错误消息明确指出押金问题，便于用户修正。
        assertTrue(unpaid.body().get("message").asString().contains("押金"), unpaid.body().toString());

        // 支付后押金条件满足，但工具状态可能仍不满足。
        // 支付后订单进入 BOOKED，押金变为 PAID。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 把工具改为维修中，模拟不可借状态。
        // 管理端可以修改工具状态，模拟维修。
        // PATCH 状态接口返回 200 才会继续后续断言。
        // 状态修改成功后工具不再可借。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "MAINTENANCE")).status());

        // 第二次取件：押金已付但工具不可用。
        // 押金已付但工具维修中，仍不能取件。
        ApiResponse unavailable = call(HttpMethod.POST, pickupUrl, admin, Map.of());
        // 仍然返回 C409，说明工具状态是独立的校验条件。
        // 冲突来源从押金变为工具状态。
        assertCode("C409", unavailable);
        // 消息包含“不可”，用于区分维修中与押金未付。
        // 错误消息包含“不可”，与押金错误区分。
        assertTrue(unavailable.body().get("message").asString().contains("不可"),
                unavailable.body().toString());
    }

    /**
     * 场景：把租出中的租赁单结束时间改到过去，连续执行两次逾期扫描。
     * 前置条件：订单已支付并取件。
     * 断言目标：订单状态变为 OVERDUE；同一业务键的逾期通知只有一条，
     * 证明扫描可重复执行而通知保持幂等。
     * 契约补充：逾期扫描按结束时间与当前时间比较，只更新满足条件的订单；
     * 通知以 OVERDUE:{rentalId} 为业务键，数据库或服务层保证幂等。
     * 连续执行两次用于验证扫描可重入，而不是依赖定时任务的单次执行。
     * 观察点：状态变为 OVERDUE 是幂等更新的结果，通知计数保持 1。
     * 业务键固定为 OVERDUE:{rentalId}，同一订单重复扫描不会新增消息。
     * 该用例同时验证“可重复执行”与“只通知一次”两个不变量。
     */
    @Test
    void mark_overdue_is_conditional_and_notifies_once() {
        // 创建工具与租赁单，并推进到已取件状态。
        // 管理员用于取件，用户用于支付。
        String admin = adminLogin();
        // 工具与订单的准备阶段。
        String user = userLogin();
        // 工具审核通过后才可预约。
        long toolId = createApprovedTool(admin, "overdue");
        // 支付是取件的前置条件。
        // 订单初始为待支付。
        long rentalId = createRental(user, toolId);
        // 支付接口成功后，订单从待支付转为已预订。
        // 支付成功后订单进入 BOOKED。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 取件接口要求管理员操作，成功后进入已取件状态。
        // 取件成功后订单进入 PICKED_UP，才可能逾期。
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());

        // 直接把结束时间改到一小时前，触发逾期条件。
        // 使用 UpdateWrapper 只改结束时间，不经过状态机。
        // 只更新测试订单，避免影响其他数据。
        // 条件限定当前订单，避免误改其他数据。
        // 结束时间设为过去，扫描时判定为逾期。
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                .eq(Rental::getId, rentalId)
                .set(Rental::getEndTime, LocalDateTime.now().minusHours(1)));

        // 第一次扫描应把状态改为 OVERDUE 并写通知。
        // 连续执行两次，验证状态更新与通知都具有幂等性。
        // 第二次扫描必须幂等：状态不变、通知不重复。
        // 第一次扫描：应更新状态并写通知。
        rentalService.markOverdue();
        // 第二次扫描：不得重复更新或重复通知。
        rentalService.markOverdue();

        // 回查状态，确认扫描确实更新了数据库。
        // 状态从 PICKED_UP 变为 OVERDUE。
        assertEquals("OVERDUE", rentalMapper.selectById(rentalId).getStatus());
        // 按业务键统计通知，避免标题或内容变化影响断言。
        // 通知按业务键计数，幂等性由此验证。
        // 业务键由类型与租赁 ID 组成，天然唯一。
        // 业务键格式固定，扫描逻辑与断言共用同一约定。
        assertEquals(1, messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizKey, "OVERDUE:" + rentalId)));
    }

    /** 辅助方法：用两天后的档期创建租赁单并返回 ID。 */
    // 使用统一的未来档期构造请求体，返回新租赁单 ID。
    // 使用真实下单接口，顺带验证档期与权限。
    private long createRental(String userToken, long toolId) {
        // 创建成功后从 data.id 读取主键。
        // 创建成功后响应 data.id 即新订单主键。
        // 下单成功后返回 data.id，作为后续操作对象。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, booking(toolId)));
        // 直接返回主键，调用方无需再查列表。
        return created.get("data").get("id").asLong();
    }

    // 档期固定在两天后到三天后，远离当前时间避免误判逾期。
    // 档期与取件人固定，只有工具 ID 随用例变化。
    private Map<String, Object> booking(long toolId) {
        // 去纳秒并对齐格式，保证服务端解析结果与请求一致。
        // Map.of 的字段顺序不影响 JSON 语义。
        // 工具 ID 决定订单关联的工具。
        return Map.of(
                "toolId", toolId,
                // 开始时间两天后，结束时间三天后，档期合法且不逾期。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 结束时间晚于开始时间，服务端会校验顺序。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人使用中文姓名，顺带验证 UTF-8 请求体。
                "pickupPerson", "王建国");
    }

    /** 辅助方法：创建唯一名称工具并审核通过。 */
    // 工具名带统一前缀与场景后缀，既唯一又可清理。
    private long createApprovedTool(String admin, String suffix) {
        // 创建工具时必须提供分类、成色、押金等必填字段。
        // 该创建请求覆盖前端表单的全部必填项。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 工具名带前缀，便于清理与识别。
                "name", PREFIX + suffix,
                // 型号为展示字段，测试不校验具体值。
                "model", "测试型号",
                // 分类 WX 与工具列表用例保持一致。
                // 分类决定列表筛选与首页分组。
                "category", "WX",
                // 成色 NEW 影响展示，不影响状态机。
                "damageLevel", "NEW",
                // 押金金额是支付用例的断言基准。
                // 押金会在支付时冻结，取消或归还后释放。
                "deposit", 100,
                // 存放位置由管理员维护。
                // 存放位置用于取件时定位工具。
                "storageArea", "测试柜",
                // 使用说明为富文本，接口按原样保存。
                "usageGuide", "<p>说明</p>",
                // 空图片列表表示工具暂无图片。
                // 图片列表为空时，前端展示默认占位图。
                "imageUrls", List.of())));
        // 主键由数据库生成并回填。
        // 创建后读取主键，用于审核与后续预约。
        long id = created.get("data").get("id").asLong();
        // 审核通过后工具才可进入预约流程。
        // 审核状态为数字枚举，APPROVED 对应可借。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        // 返回工具 ID，供后续预约与状态修改。
        // 主键返回后，调用方可继续审核或预约。
        return id;
    }

    /**
     * 辅助方法：用双线程池与两个倒计时栅栏让两个请求尽量同时发出。
     * 先等待双方就绪再统一放行，降低线程调度造成的伪串行。
     * 两个 Future 分别承载一次请求，get 时统一传播异常与超时。
     * 线程池在 finally 中关闭，测试失败也不会残留线程。
     */
    // 双线程池只用于本方法，finally 中立即关闭。
    private List<ApiResponse> concurrently(Supplier<ApiResponse> first,
                                           Supplier<ApiResponse> second) throws Exception {
        // ready 计数到 2 表示两个线程都已进入等待。
        // 固定两个线程，足够制造一次竞争。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // go 是统一放行信号，只释放一次。
        // ready 用于确认两个任务都已准备执行。
        CountDownLatch ready = new CountDownLatch(2);
        // go 只释放一次，保证两个请求尽量同时出发。
        CountDownLatch go = new CountDownLatch(1);
        try {
            // 两个任务都先 await 再执行实际请求。
            // 两个任务共享 ready 与 go 两个栅栏。
            Future<ApiResponse> a = pool.submit(() -> await(ready, go, first));
            // 第二个任务与第一个使用相同同步策略。
            Future<ApiResponse> b = pool.submit(() -> await(ready, go, second));
            // 最多等 10 秒确认双方就绪，避免测试永久挂起。
            // 双方就绪后才允许继续，避免伪串行。
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            // 放行后两个请求尽可能同时进入业务逻辑。
            // countDown 唤醒两个等待中的任务。
            go.countDown();
            // 等待结果最多 30 秒，超时视为测试失败。
            // get 会阻塞到任务完成，同时带超时保护。
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            // shutdownNow 中断仍阻塞的任务，避免线程泄漏。
            // 无论成功失败都关闭线程池，避免线程泄漏。
            pool.shutdownNow();
        }
    }

    /**
     * 辅助方法：线程就绪后等待放行信号，再执行实际请求。
     * 保证两个请求在同一时刻进入业务逻辑。
     * 先报告就绪再等待放行，保证两个请求尽可能同时进入业务逻辑。
     * 放行等待带超时，栅栏计数错配时会快速失败而非永久阻塞。
     */
    // 两个计数栅栏由 concurrent 方法创建并传入。
    // 该方法不吞异常：任务失败会通过 Future.get 传播。
    private ApiResponse await(CountDownLatch ready, CountDownLatch go,
                              Supplier<ApiResponse> call) throws InterruptedException {
        // 先报告就绪，再等待放行。
        ready.countDown();
        // 放行信号最多等 10 秒，防止栅栏错配。
        // 超时后仍继续执行，由后续断言暴露栅栏失败。
        go.await(10, TimeUnit.SECONDS);
        // 真正发起 HTTP 请求。
        // 请求异常由 Future 包装后抛给调用方。
        return call.get();
    }

    /** 辅助方法：从响应体读取业务码。 */
    // 业务码位于 body.code，缺失时会抛出空指针。
    // 用于错误分支与成功分支的轻量业务码提取。
    private String code(ApiResponse response) {
        // 缺失 code 字段会抛空指针，提示响应契约被破坏。
        return response.body().get("code").asString();
    }

    /**
     * 辅助方法：断言业务码，并在期望 C409 时同时校验 HTTP 409。
     * 统一冲突语义，避免只返回业务码而状态码错误。
     * 业务码是服务层契约，HTTP 状态是传输层契约，两者都要校验。
     * 只有期望 C409 时强制 HTTP 409，其他业务码交由调用方决定。
     */
    // 先比较业务码，失败信息包含完整响应体。
    private void assertCode(String code, ApiResponse response) {
        // C409 必须同时是 HTTP 409，避免只改业务码。
        assertEquals(code, code(response), response.body().toString());
        // 其他业务码只校验业务层，不强制 HTTP 状态。
        // 仅当调用方期望 C409 时才强制 HTTP 409。
        if ("C409".equals(code)) {
            assertEquals(409, response.status(), response.body().toString());
        }
    }

    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    // 成功响应必须同时满足 HTTP 200 与业务码 A200。
    private JsonNode ok(ApiResponse response) {
        // 返回 body，供调用方读取 data.id。
        assertEquals(200, response.status(), response.body().toString());
        // 业务码成功与 HTTP 200 必须同时成立。
        assertEquals("A200", code(response), response.body().toString());
        // 调用方从这里继续读取 data.id 或字段。
        return response.body();
    }

    /** 辅助方法：种子管理员登录并返回令牌。 */
    // 管理员账号来自启动种子数据。
    private String adminLogin() {
        // 管理员登录端点与用户端分开，令牌权限不同。
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    /** 辅助方法：种子用户密码登录并返回令牌。 */
    // 居民账号来自启动种子数据，测试不自行注册。
    private String userLogin() {
        // 用户登录使用手机号 + 密码，不使用验证码。
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    // 登录请求为 POST + JSON，不带既有令牌。
    private String login(String url, Map<String, Object> body) {
        // 失败时打印响应体，便于定位种子数据缺失。
        // 登录请求不带旧令牌，属于匿名调用。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        // tokenValue 作为后续 Bearer 令牌。
        assertEquals(200, response.status(), response.body().toString());
        // 令牌字符串由 Sa-Token 签发，测试不解析其内容。
        return response.body().get("data").get("tokenValue").asString();
    }

    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // exchange 允许读取任意状态码，4xx 不抛异常。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 请求规格在每次调用时新建，不复用可变对象。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌的请求统一使用 Bearer 方案。
        // 有令牌时附加标准 Bearer 认证头。
        // 匿名请求保持无认证头，用于鉴权用例。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 有请求体时声明 JSON 类型。
        // 无 body 的请求不会写入 Content-Type。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 手动读取响应流，空体统一解析为 {}。
        // 回调内手动读取流，避免 retrieve 对 4xx 抛异常。
        return spec.exchange((request, response) -> {
            // try-with-resources 确保响应流被关闭。
            // 响应流必须关闭，异常路径同样释放连接。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取，中文错误消息不会乱码。
                // 空体替换为 {}，让 JsonNode 访问保持安全。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 状态码与 JSON 一起返回，供双层断言。
                return new ApiResponse(response.getStatusCode().value(),
                        // 解析结果作为 body 返回，不在此处断言业务码。
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 异常路径不返回半成品响应，避免误判成功。
            // 解析失败视为测试基础设施错误，直接上抛。
            } catch (IOException e) {
                // 读流失败属于测试基础设施错误，直接失败。
                throw new IllegalStateException(e);
            }
        });
    }
}
