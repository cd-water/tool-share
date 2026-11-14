// 租赁结算与归还验收集成测试：覆盖费用、退款、应收、减免与并发。
package com.cdwater.toolshare.service;

// MyBatis-Plus 查询构造器：按租赁单筛选费用与支付流水。
// Lambda 形式绑定实体方法引用，避免硬编码列名。
// 用于清理时按租赁单筛选费用与支付流水。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// MyBatis-Plus 更新构造器：直接改库调整租赁档期。
// 绕过业务校验，精确构造按时或逾期场景。
// 用于直接改库调整租赁档期。
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
// 集成测试基类：提供完整 Spring 上下文与共享测试环境。
// 继承后可复用随机端口、数据库与登录能力。
// 提供随机端口与共享上下文。
import com.cdwater.toolshare.AbstractIntegrationTest;
// 测试存储配置：用内存实现替换对象存储。
// 上传图片不落真实 S3，测试可离线运行。
// 存储实现由测试配置覆盖。
import com.cdwater.toolshare.TestStorageConfig;
// 报修实体：存在未终结报修时归还规则会变化。
// 清理工具前需先删除关联报修。
// 未终结报修会影响带伤归还逻辑。
import com.cdwater.toolshare.module.damage.DamageReport;
// 报修 Mapper：按工具 ID 删除测试报修数据。
// 保证清理链路完整。
// 清理报修时按工具 ID 删除。
import com.cdwater.toolshare.module.damage.DamageReportMapper;
// 支付流水实体：记录押金入账、退款与应收。
// 流水条数与金额是结算断言核心。
// 记录押金入账、退款与应收。
import com.cdwater.toolshare.module.rental.PaymentRecord;
// 支付流水 Mapper：按租赁单查询与清理流水。
// 用于核对退款与应收记录。
// 断言流水条数与金额。
import com.cdwater.toolshare.module.rental.PaymentRecordMapper;
// 租赁单实体：结算结果会写回该实体字段。
// 归还后需回查状态、费用与退款金额。
// 结算结果写回租赁单字段。
import com.cdwater.toolshare.module.rental.Rental;
// 费用流水实体：记录逾期、赔偿与减免金额。
// 费用类型决定账本归类。
// 记录逾期、赔偿与减免金额。
import com.cdwater.toolshare.module.rental.RentalFee;
// 费用流水 Mapper：按租赁单查询与清理费用。
// 费用类型与金额是核心断言对象。
// 费用类型与金额是核心断言。
import com.cdwater.toolshare.module.rental.RentalFeeMapper;
// 租赁单 Mapper：调整档期并回查结算快照。
// 也可按工具 ID 批量清理测试订单。
// 调整档期并回查结算快照。
import com.cdwater.toolshare.module.rental.RentalMapper;
// 工具实体：清理时按名称前缀定位测试工具。
// 工具押金金额由用例参数决定。
// 押金金额由用例参数控制。
import com.cdwater.toolshare.module.tool.Tool;
// 工具日志实体：删除工具前先清理日志。
// 日志记录工具状态变化。
// 删除工具前先清理日志。
import com.cdwater.toolshare.module.tool.ToolLog;
// 工具日志 Mapper：按工具 ID 删除日志。
// 保持共享测试库干净。
// 按工具 ID 删除日志。
import com.cdwater.toolshare.module.tool.ToolLogMapper;
// 工具 Mapper：创建、审核与删除测试工具。
// 工具必须审核通过才可被预约。
// 创建、审核与清理测试工具。
import com.cdwater.toolshare.module.tool.ToolMapper;
// 每个用例后执行清理，防止数据串扰。
// 即使断言失败也会运行。
// 清理逻辑必须幂等。
import org.junit.jupiter.api.AfterEach;
// 每个用例前创建 HTTP 客户端。
// 客户端指向随机端口。
// 每个用例前重建客户端。
import org.junit.jupiter.api.BeforeEach;
// JUnit 测试注解：标记独立用例。
// 每个方法覆盖一类结算规则。
// 每个用例相互独立。
import org.junit.jupiter.api.Test;
// Spring 依赖注入：注入 Mapper 与端口。
// 测试上下文复用生产 Bean。
// 注入失败会让测试启动失败。
import org.springframework.beans.factory.annotation.Autowired;
// 启动完整应用并使用随机端口。
// 贴近真实部署环境。
// 随机端口支持并发测试。
import org.springframework.boot.test.context.SpringBootTest;
// 注入随机端口，供 HTTP 客户端拼接地址。
// 避免固定端口冲突。
// 端口在上下文启动后固定。
import org.springframework.boot.test.web.server.LocalServerPort;
// 导入测试存储配置，替换对象存储实现。
// 只影响测试上下文。
import org.springframework.context.annotation.Import;
// HTTP 方法枚举：构造 GET/POST/PATCH 请求。
// 与真实接口路径保持一致。
// 覆盖查询与写入请求。
import org.springframework.http.HttpMethod;
// 媒体类型常量：请求体按 JSON 发送。
// 与后端 Content-Type 约定一致。
// 请求体统一为 JSON。
import org.springframework.http.MediaType;
// Spring 同步 HTTP 客户端：端到端调用接口。
// 验证鉴权、状态码与响应结构。
// 不依赖浏览器或前端产物。
import org.springframework.web.client.RestClient;
// JSON 树模型：按字段路径断言响应。
// 免去为测试定义额外 DTO。
// 按字段路径断言响应。
import tools.jackson.databind.JsonNode;
// JSON 解析器：把响应体解析为 JsonNode。
// 空响应按空对象处理。
import tools.jackson.databind.ObjectMapper;

// IO 异常：读取响应流时可能抛出。
// 捕获后转为非受检异常。
// 主要出现在读取响应体时。
import java.io.IOException;
// 输入流：读取接口返回的字节。
// try-with-resources 保证关闭。
// 响应体可能为空。
import java.io.InputStream;
// 高精度金额类型：构造押金与费用断言。
// 避免浮点误差影响金额比较。
// 金额断言使用 compareTo。
import java.math.BigDecimal;
// 字符集常量：响应体按 UTF-8 解码。
// 防止中文内容乱码。
// 中文响应需要正确解码。
import java.nio.charset.StandardCharsets;
// 本地时间：构造归还时刻与租赁档期。
// 固定到秒，便于与接口格式对齐。
// 时间比较基于同一时区。
import java.time.LocalDateTime;
// 日期格式化器：把时间转为接口字符串。
// 去掉纳秒，避免精度差异。
// 秒级精度足以验证取整。
import java.time.format.DateTimeFormatter;
// HashMap：在归还请求体中透传额外字段。
// 支持减免开关、带伤归还与伪造金额。
// 透传减免与伪造金额字段。
import java.util.HashMap;
// 集合工具：构造空图片列表与流水列表。
// 遍历租户数据时也使用 List。
// 用于构造空列表。
import java.util.List;
// Map 工具：快速拼装请求体与登录参数。
// 使用不可变 Map 减少意外修改。
// 登录参数也使用 Map。
import java.util.Map;
// 并发同步：就绪与放行两个计数器。
// 让两个归还请求尽量同时进入业务逻辑。
// 并发用例的同步工具。
import java.util.concurrent.CountDownLatch;
// 线程池：并发执行两个归还请求。
// 测试结束必须关闭。
// 并发用例的线程池。
import java.util.concurrent.ExecutorService;
// 线程池工厂：固定两个线程。
// 线程数与并发请求数一致。
// 固定两个线程。
import java.util.concurrent.Executors;
// Future：获取并发请求结果。
// 带超时读取，避免测试挂起。
// 获取并发请求结果。
import java.util.concurrent.Future;
// 时间单位：为等待与结果读取设置超时。
// 防止死锁导致测试无限等待。
// 为等待设置超时，避免挂起。
import java.util.concurrent.TimeUnit;
// 函数式接口：把并发任务作为参数传入。
// 便于复用同一套并发框架。
// 以函数式接口传入并发任务。
import java.util.function.Supplier;

// 断言相等：覆盖状态、金额、条数与业务码。
// 失败消息携带实际响应，便于排查。
// 断言状态、金额与业务码。
import static org.junit.jupiter.api.Assertions.assertEquals;
// 断言为真：校验超时就绪与错误提示。
// 失败时输出上下文。
// 断言超时与冲突计数。
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租赁结算与归还验收集成测试。
 * <p>
 * 测试范围：归还时的逾期费、损坏赔偿、押金退还/应收、减免记录与流水一致性，
 * 以及并发归还、带伤归还和时间校验。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子账号。
 * 关键不变量：金额只由服务端计算并四舍五入到小时；费用封顶押金，
 * 超出部分形成 RECEIVABLE 应收；减免必须带原因并单独落 WAIVER 流水；
 * 同一租赁单并发归还只能成功一次，退款流水也只写一条。
 * <p>
 * 覆盖路径：按时归还、逾期取整、费用封顶、损坏赔偿、减免、时间校验与并发。
 * 金额规则：逾期按小时向上取整，费用总额不超过押金，超出部分转应收。
 * 账本规则：计算金额与减免金额分离，减免必须带原因并单独落 WAIVER 流水。
 * 并发规则：同一租赁单只能成功归还一次，退款流水也只写一条。
 * 时间规则：归还时间必须在租赁开始与当前时间之间，防止时间倒挂。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalSettlementIntegrationTest extends AbstractIntegrationTest {

    // 统一时间格式：与接口契约一致，精确到秒。
    // 秒级精度足以验证按小时取整规则。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 工具名称统一前缀：清理时按前缀定位。
    // 后缀区分不同用例，避免名称冲突。
    private static final String PREFIX = "IT-SETTLE-";

    // 随机端口由 Spring 分配。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    @Autowired
    // 工具 Mapper：创建、审核并清理测试工具。
    // 押金金额由用例指定，用于覆盖不同结算场景。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    ToolMapper toolMapper;
    @Autowired
    // 工具日志 Mapper：删除工具前先清理日志。
    // 保持共享测试库干净。
    /** 工具日志 Mapper，清理工具前先删关联日志。 */
    ToolLogMapper toolLogMapper;
    @Autowired
    // 租赁单 Mapper：调整档期并断言结算快照。
    // 结算结果直接写入租赁单字段。
    /** 租赁单 Mapper，用于调整档期与断言结算快照。 */
    RentalMapper rentalMapper;
    @Autowired
    // 费用流水 Mapper：核对逾期、赔偿与减免记录。
    // 费用类型与金额是核心断言对象。
    /** 费用流水 Mapper，用于核对逾期/赔偿/减免记录。 */
    RentalFeeMapper rentalFeeMapper;
    @Autowired
    // 支付流水 Mapper：核对押金、退款与应收。
    // 流水条数用于确认没有重复退款。
    /** 支付流水 Mapper，用于核对押金、退款与应收。 */
    PaymentRecordMapper paymentRecordMapper;
    @Autowired
    // 报修 Mapper：构造未终结报修场景并清理。
    // 带伤归还逻辑依赖未完成的报修记录。
    /** 报修 Mapper，用于构造未终结报修场景并清理。 */
    DamageReportMapper damageReportMapper;

    RestClient client;
    // HTTP 客户端按随机端口重建。
    // ObjectMapper 复用于解析响应。
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    // 客户端在用例间复用同一随机端口。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // 指向随机端口，模拟真实前端调用。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按名称前缀清理测试工具及其费用、支付流水、报修、租赁与日志。
     * 删除顺序从子表到主表，保证共享测试库不残留结算数据。
     * 先按工具前缀定位工具，再沿租赁单删除费用与支付流水。
     * 报修、租赁、工具日志与工具按依赖顺序删除。
     * 所有删除都是按外键条件精确执行，不做全表清理。
     */
    @AfterEach
    void cleanUp() {
        // 按统一前缀查询本类创建的工具。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        // 逐个工具处理，限定清理范围。
        for (Tool tool : tools) {
            // 查询该工具下的所有租赁单。
            List<Rental> rentals = rentalMapper.selectList(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 逐笔清理租赁单的关联账本。
            for (Rental rental : rentals) {
                // 删除费用流水。
                rentalFeeMapper.delete(new LambdaQueryWrapper<RentalFee>()
                        .eq(RentalFee::getRentalId, rental.getId()));
                // 删除支付流水。
                paymentRecordMapper.delete(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getRentalId, rental.getId()));
            }
            // 删除工具关联的报修。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 删除租赁单。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 删除工具日志。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 最后删除工具。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：租赁单按时归还且验收完好。
     * 前置条件：档期被改成已开始、尚未结束；工具已支付并取件。
     * 断言目标：订单变为 RETURNED，押金 REFUNDED；逾期费与赔偿均为 0，
     * 退款等于押金；不产生费用流水，支付流水恰为押金入账与退还两条，无应收。
     * <p>
     * 按时完好归还时，押金应全额退还，且不产生任何费用流水。
     * 支付流水应恰好包含押金入账与押金退还两条。
     * 订单状态与押金状态必须同步推进为 RETURNED/REFUNDED。
     * 实际归还时间应原样落库，供审计核对。
     * 该用例是所有结算规则的基线。
     */
    @Test
    void on_time_intact_return_refunds_full_deposit_and_writes_ledger() {
        // 管理员登录，用于创建工具、取件与归还。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 走完创建、支付、取件，得到可归还租赁。
        long rentalId = pickedUpRental(admin, user, "on-time", "100");
        // 固定当前时刻并去掉纳秒。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 租期已开始且未结束，归还判定为按时。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        // 按时完好归还。
        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        // 归还接口应返回 200。
        assertEquals(200, response.status(), response.body().toString());

        // 回查租赁单，断言结算快照。
        Rental rental = rentalMapper.selectById(rentalId);
        // 订单状态变为 RETURNED。
        assertEquals("RETURNED", rental.getStatus());
        // 押金状态变为 REFUNDED。
        assertEquals("REFUNDED", rental.getDepositStatus());
        // 无逾期费。
        assertEquals(0, rental.getOverdueFee().signum());
        // 无损坏赔偿。
        assertEquals(0, rental.getDamageFee().signum());
        // 退款金额等于押金 100 元。
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("100.00")));
        // 实际归还时间原样落库。
        assertEquals(0, rental.getActualReturnTime().compareTo(now));

        // 按时完好归还不应产生费用流水。
        assertEquals(0, fees(rentalId).size());
        // 查询支付流水。
        List<PaymentRecord> payments = payments(rentalId);
        // 恰好两条：押金入账与退还。
        assertEquals(2, payments.size(), payments.toString());
        // 第一条为押金入账 100 元。
        assertPayment(payments, "DEPOSIT_IN", "100.00");
        // 第二条为押金退还 100 元。
        assertPayment(payments, "DEPOSIT_REFUND", "100.00");
        // 无应收金额。
        assertEquals(0, receivable(rentalId).signum());
    }

    /**
     * 场景：归还时间比约定结束时间晚 1 分钟。
     * 前置条件：逾期计费按小时向上取整，每小时 5 元。
     * 断言目标：产生 5 元 OVERDUE 流水，退款 95 元，退款流水金额同步。
     * <p>
     * 逾期费按小时向上取整：晚 1 分钟也按 1 小时计。
     * 每小时费率固定，便于断言具体金额。
     * 退款金额等于押金减去逾期费。
     * 退款流水金额必须与租赁单上的退款字段一致。
     * 该用例验证取整边界与账本同步。
     */
    @Test
    void one_minute_late_rounds_up_to_one_hour() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备押金 100 元的租赁单。
        long rentalId = pickedUpRental(admin, user, "round-up", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设为一分钟前，触发最小逾期。
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        // 归还时间晚 1 分钟。
        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        // 请求应成功。
        assertEquals(200, response.status(), response.body().toString());

        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 逾期费按 1 小时计，为 5 元。
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        // 退款 100 - 5 = 95 元。
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("95.00")));
        // 费用流水为 OVERDUE 5 元。
        assertFee(rentalId, "OVERDUE", "5.00");
        // 退款流水金额同步为 95 元。
        assertPayment(payments(rentalId), "DEPOSIT_REFUND", "95.00");
    }

    /**
     * 场景：归还时间晚 30 小时，按规则费用远超押金。
     * 前置条件：逾期费上限为押金 100 元。
     * 断言目标：逾期费封顶 100 元，退款为 0 且没有押金退还流水，
     * 防止押金被超额扣减。
     * <p>
     * 逾期 30 小时产生的费用远超押金 100 元。
     * 费用封顶规则要求最多扣到押金金额。
     * 封顶后退款为 0，且不得出现押金退还流水。
     * 该用例防止押金被超额扣减或出现负退款。
     * 费用流水仍记录封顶后的 100 元。
     */
    @Test
    void overdue_fee_is_capped_at_deposit() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备押金 100 元的租赁单。
        long rentalId = pickedUpRental(admin, user, "cap", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间为 30 小时前，逾期费远超押金。
        setSlot(rentalId, now.minusDays(3), now.minusHours(30));

        // 执行归还。
        ApiResponse response = returnRental(admin, rentalId, now, "INTACT", Map.of());
        // 请求应成功。
        assertEquals(200, response.status(), response.body().toString());

        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 逾期费封顶为押金 100 元。
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("100.00")));
        // 退款为 0，不会出现负退款。
        assertEquals(0, rental.getRefundAmount().signum());
        // 费用流水仍记录 100 元逾期费。
        assertFee(rentalId, "OVERDUE", "100.00");
        // 不得生成押金退还流水。
        assertEquals(0, payments(rentalId).stream()
                .filter(p -> "DEPOSIT_REFUND".equals(p.getRecordType())).count());
    }

    /** 辅助方法：汇总指定租赁单的 RECEIVABLE 应收金额。 */
    @Test
    void severe_damage_defaults_to_50_and_excess_becomes_receivable() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备押金 100 元的租赁单。
        long rentalId = pickedUpRental(admin, user, "receivable", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 逾期 30 小时，逾期费先封顶 100 元。
        setSlot(rentalId, now.minusDays(3), now.minusHours(30));

        // 严重损坏归还。
        ApiResponse response = returnRental(admin, rentalId, now, "SEVERE", Map.of());
        // 请求应成功。
        assertEquals(200, response.status(), response.body().toString());

        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 逾期费封顶 100 元。
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("100.00")));
        // 严重损坏默认赔偿 50 元。
        assertEquals(0, rental.getDamageFee().compareTo(new BigDecimal("50.00")));
        // 押金不足以覆盖，退款为 0。
        assertEquals(0, rental.getRefundAmount().signum());
        // 逾期费流水 100 元。
        assertFee(rentalId, "OVERDUE", "100.00");
        // 赔偿流水 50 元。
        assertFee(rentalId, "DAMAGE", "50.00");
        // 超出押金的 50 元形成应收。
        assertPayment(payments(rentalId), "RECEIVABLE", "50.00");
    }

    /**
     * 场景：先只勾选减免但不填原因，再补齐原因同时减免逾期与赔偿。
     * 前置条件：减免必须附带原因并单独记账。
     * 断言目标：无原因返回 400/C400 且订单仍为 PICKED_UP；
     * 补齐原因后归还成功，原费用金额保持不变，另生成合计 55 元的 WAIVER 流水，
     * 押金全额退还，审计时能区分“计算金额”与“实际减免”。
     * <p>
     * 减免开关必须同时提供原因，否则请求被拒绝。
     * 被拒绝时订单状态不得推进，仍保持取件中。
     * 减免不改变原始费用金额，而是额外落 WAIVER 流水。
     * 逾期费 5 元与赔偿 50 元合计减免 55 元。
     * 押金全额退还，审计可区分计算金额与实际减免。
     */
    @Test
    void waiver_requires_reason_and_is_recorded_as_waiver_ledger() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备押金 100 元的租赁单。
        long rentalId = pickedUpRental(admin, user, "waiver", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设为一分钟前，产生 5 元逾期费。
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        // 先只传减免开关、不传原因。
        // 先验证缺少减免原因时必须拒绝，且订单状态不得变化。
        // 缺少原因时必须拒绝。
        ApiResponse noReason = returnRental(admin, rentalId, now, "SEVERE",
                // 请求体只含 waiveOverdue 开关。
                Map.of("waiveOverdue", true));
        // 返回 400。
        assertEquals(400, noReason.status(), noReason.body().toString());
        // 业务码为 C400。
        assertEquals("C400", code(noReason));
        // 订单状态保持 PICKED_UP，未推进结算。
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());

        // 补齐原因，同时减免逾期与赔偿。
        ApiResponse waived = returnRental(admin, rentalId, now, "SEVERE",
                // 两个减免开关都打开。
                Map.of("waiveOverdue", true, "waiveDamage", true,
                        // 减免原因必填，用于审计。
                        "waiverReason", "社区活动减免"));
        // 本次归还应成功。
        assertEquals(200, waived.status(), waived.body().toString());

        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 计算金额保持原值，减免单独落流水。
        // 逾期费仍为 5 元。
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        // 赔偿费仍为 50 元。
        assertEquals(0, rental.getDamageFee().compareTo(new BigDecimal("50.00")));
        // 押金全额退还 100 元。
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("100.00")));
        // 单独汇总 WAIVER 流水。
        // 减免金额单独汇总，验证与原始计算金额分离记账。
        // 从费用流水中汇总减免金额。
        BigDecimal waivedTotal = fees(rentalId).stream()
                // 只保留 WAIVER 类型的流水。
                .filter(f -> "WAIVER".equals(f.getFeeType()))
                // 取出减免金额。
                .map(RentalFee::getAmount)
                // 从 0 开始累加，得到减免总额。
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 减免总额 5 + 50 = 55 元。
        assertEquals(0, waivedTotal.compareTo(new BigDecimal("55.00")));
        // 押金退还流水仍为 100 元。
        assertPayment(payments(rentalId), "DEPOSIT_REFUND", "100.00");
    }

    /**
     * 场景：分别提交晚于当前时间的归还时间和早于租赁开始的时间。
     * 前置条件：归还时间必须位于租赁开始时间与当前时间之间。
     * 断言目标：未来时间返回 422，早于开始返回 400 且提示含“开始”；
     * 两次失败都不推进订单状态，避免时间倒挂污染结算。
     * <p>
     * 归还时间不能晚于当前时刻，否则返回 422。
     * 归还时间不能早于租赁开始时间，否则返回 400。
     * 错误提示应明确指出问题与“开始”时间有关。
     * 两次失败都不得推进订单状态。
     * 该用例防止时间倒挂污染结算数据。
     */
    @Test
    void return_time_must_not_be_in_future_or_before_rental_start() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "time-guard", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 租期一小时前开始、一小时后结束。
        setSlot(rentalId, now.minusHours(1), now.plusHours(1));

        // 提交晚于当前时刻的归还时间。
        ApiResponse future = returnRental(admin, rentalId, now.plusHours(2), "INTACT", Map.of());
        // 未来时间返回 422。
        assertEquals(422, future.status(), future.body().toString());

        // 提交早于租赁开始时间的归还时间。
        ApiResponse beforeStart = returnRental(admin, rentalId, now.minusHours(2), "INTACT", Map.of());
        // 早于开始返回 400。
        assertEquals(400, beforeStart.status(), beforeStart.body().toString());
        // 错误提示应包含“开始”，明确时间下界。
        assertTrue(beforeStart.body().get("message").asString().contains("开始"),
                beforeStart.body().toString());
        // 两次失败都未推进订单状态。
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());
    }

    /**
     * 场景：产生一笔逾期费后查询租赁详情。
     * 前置条件：费用与支付流水均已落库。
     * 断言目标：详情返回 fees 与 payments 两组账本；
     * 费用类型与中文名正确、金额 5 元；支付流水按押金入账、退还顺序展示。
     * <p>
     * 租赁详情返回 fees 与 payments 两组账本。
     * 费用项需包含类型、中文名与金额。
     * 支付流水按押金入账、退还的顺序展示。
     * 中文类型名用于前端直接展示，避免映射遗漏。
     * 该用例验证查询接口的聚合结构。
     */
    @Test
    void rental_detail_exposes_fee_and_payment_ledger() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "ledger", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设为一分钟前，产生逾期费。
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));
        // 归还成功，生成费用与支付流水。
        assertEquals(200, returnRental(admin, rentalId, now, "INTACT", Map.of()).status());

        // 查询租赁详情。
        ApiResponse detail = call(HttpMethod.GET, "/api/user/rentals/" + rentalId, user, null);
        // 详情接口返回 200。
        assertEquals(200, detail.status(), detail.body().toString());
        // 取出 data 字段。
        JsonNode data = detail.body().get("data");

        // 费用账本只有一条逾期费。
        assertEquals(1, data.get("fees").size(), data.toString());
        // 费用类型为 OVERDUE。
        assertEquals("OVERDUE", data.get("fees").get(0).get("feeType").asString());
        // 中文名用于前端展示。
        assertEquals("逾期费", data.get("fees").get(0).get("feeTypeName").asString());
        // 金额为 5 元。
        assertEquals(0, data.get("fees").get(0).get("amount").decimalValue()
                .compareTo(new BigDecimal("5.00")));

        // 支付账本包含押金入账与退还两条。
        assertEquals(2, data.get("payments").size(), data.toString());
        // 第一条为押金入账。
        assertEquals("DEPOSIT_IN", data.get("payments").get(0).get("recordType").asString());
        // 第二条为押金退还。
        assertEquals("DEPOSIT_REFUND", data.get("payments").get(1).get("recordType").asString());
        // 退还类型的中文名正确。
        assertEquals("退还押金", data.get("payments").get(1).get("recordTypeName").asString());
    }

    /**
     * 场景：租赁单存在未终结报修时，管理员先按完好归还，再显式带伤归还。
     * 前置条件：报修单已提交但未完成处理。
     * 断言目标：普通归还返回 409 且订单保持取件中；
     * 勾选 returnWithDamage 后归还成功，备注说明带伤归还，工具保持维修中，
     * 防止未处理损坏被当作完好重新上架。
     * <p>
     * 存在未终结报修时，普通归还必须被拒绝并返回 409。
     * 管理员需显式勾选 returnWithDamage 才能带伤归还。
     * 带伤归还时工具保持 MAINTENANCE 状态，不能重新上架。
     * 归还备注应记录带伤原因，便于后续处理。
     * 该用例防止未处理损坏被当作完好工具流转。
     */
    @Test
    void unfinished_report_requires_explicit_damage_return() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "open-report", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 设置按时归还档期。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        // 用户提交一笔未终结的报修。
        assertEquals(200, call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                // 报修内容包含损坏部件与描述。
                "rentalId", rentalId, "damagePart", "电机", "description", "异响",
                // 不附带图片。
                "images", List.of())).status());

        // 管理员先尝试按完好归还。
        ApiResponse rejected = returnRental(admin, rentalId, now, "INTACT", Map.of());
        // 存在未终结报修时应返回 409。
        assertEquals(409, rejected.status(), rejected.body().toString());
        // 订单仍保持取件中。
        assertEquals("PICKED_UP", rentalMapper.selectById(rentalId).getStatus());

        // 显式勾选带伤归还。
        ApiResponse accepted = returnRental(admin, rentalId, now, "INTACT",
                // returnWithDamage 表示管理员已知悉损坏。
                Map.of("returnWithDamage", true));
        // 带伤归还成功。
        assertEquals(200, accepted.status(), accepted.body().toString());
        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 订单状态变为 RETURNED。
        assertEquals("RETURNED", rental.getStatus());
        // 备注记录带伤归还原因。
        assertEquals("存在未终结报修，带伤归还", rental.getReturnRemark());
        // 工具应保持维修中，不能直接重新上架。
        assertEquals("MAINTENANCE",
                toolMapper.selectById(rental.getToolId()).getStatus(),
                "带伤归还时工具保持维修中");
    }

    /**
     * 场景：归还请求伪造 overdueFee=0、damageFee=0。
     * 前置条件：金额只允许服务端按规则计算。
     * 断言目标：实际仍按迟到一分钟收取 5 元并退款 95 元，
     * 客户端金额字段不参与结算，防止篡改费用。
     * <p>
     * 请求体携带 overdueFee=0、damageFee=0 属于客户端伪造。
     * 服务端必须忽略客户端金额，按规则重新计算。
     * 实际仍按迟到一分钟收取 5 元并退款 95 元。
     * 该用例验证金额只由服务端计算，防止篡改费用。
     * 结算结果以数据库字段为准，而非请求参数。
     */
    @Test
    void client_supplied_amounts_are_ignored() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "ignore-client", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设为一分钟前，实际应产生逾期费。
        setSlot(rentalId, now.minusHours(3), now.minusMinutes(1));

        // 请求体伪造 overdueFee 与 damageFee 为 0。
        ApiResponse response = returnRental(admin, rentalId, now, "INTACT",
                // 额外字段透传到归还接口。
                Map.of("overdueFee", 0, "damageFee", 0));
        // 请求本身成功，但金额应由服务端重算。
        assertEquals(200, response.status(), response.body().toString());

        // 回查租赁单。
        Rental rental = rentalMapper.selectById(rentalId);
        // 逾期费仍为 5 元，客户端金额被忽略。
        assertEquals(0, rental.getOverdueFee().compareTo(new BigDecimal("5.00")));
        // 退款仍为 95 元。
        assertEquals(0, rental.getRefundAmount().compareTo(new BigDecimal("95.00")));
    }

    /**
     * 场景：两个线程同时对同一租赁单发起归还。
     * 前置条件：请求通过栅栏同时放行，订单仍为取件中。
     * 断言目标：一单成功、一单 409；订单只归还一次，
     * 押金退还流水也只写一条，防止重复退款。
     * <p>
     * 两个线程通过栅栏同时发起归还，模拟并发重复提交。
     * 状态机或行锁应保证只有一个请求成功。
     * 另一个请求返回 409 冲突，不得重复结算。
     * 押金退还流水只能写一条，防止重复退款。
     * 该用例验证并发下的幂等与账本一致性。
     */
    @Test
    void concurrent_return_only_one_succeeds_and_ledger_written_once() throws Exception {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "concurrent", "100");
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 设置按时归还档期。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        // 两个归还请求通过栅栏同时放行。
        // 两个归还请求同时放行，模拟重复提交或并发操作。
        // 并发执行两个归还请求。
        List<ApiResponse> results = concurrently(
                // 第一个请求。
                () -> returnRental(admin, rentalId, now, "INTACT", Map.of()),
                // 第二个请求。
                () -> returnRental(admin, rentalId, now, "INTACT", Map.of()));

        // 并发结果必须恰好一成一败。
        // 必须恰好一个成功，另一个以冲突结束。
        // 恰好一个请求返回 200。
        assertEquals(1, results.stream().filter(r -> r.status() == 200).count(), results.toString());
        // 另一个请求返回 409 冲突。
        assertEquals(1, results.stream().filter(r -> r.status() == 409).count(), results.toString());
        // 订单状态只推进到 RETURNED 一次。
        assertEquals("RETURNED", rentalMapper.selectById(rentalId).getStatus());
        // 押金退还流水只有一条。
        assertEquals(1, payments(rentalId).stream()
                .filter(p -> "DEPOSIT_REFUND".equals(p.getRecordType())).count());
    }

    /**
     * 辅助方法：创建工具、下单、支付并取件，返回可直接归还的租赁单 ID。
     * 把结算用例的前置流程收敛到一处，避免重复拼装请求。
     * 支付与取件都断言成功，保证租赁单处于可归还状态。
     * 押金金额由调用方指定，便于覆盖不同结算边界。
     */
    private long pickedUpRental(String admin, String user, String suffix, String deposit) {
        // 创建并审核测试工具。
        long toolId = createApprovedTool(admin, suffix, deposit);
        // 创建两天后开始的租赁单。
        long rentalId = createRental(user, toolId);
        // 支付押金。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 管理员取件。
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        // 返回租赁单 ID。
        return rentalId;
    }

    // 所有时间都精确到秒，避免格式差异。
    /** 辅助方法：直接改库调整租赁起止时间，精确构造准时/逾期场景。 */
    private void setSlot(long rentalId, LocalDateTime start, LocalDateTime end) {
        // 直接更新租赁单时间字段。
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                // 按租赁单 ID 定位。
                .eq(Rental::getId, rentalId)
                // 设置开始时间。
                .set(Rental::getStartTime, start)
                // 设置结束时间，决定按时或逾期。
                .set(Rental::getEndTime, end));
    }

    /**
     * 辅助方法：组装归还请求体并调用管理端归还接口。
     * 额外字段透传，便于测试减免开关与客户端伪造金额。
     * 额外字段用于透传减免开关、带伤归还与伪造金额等测试参数。
     * 实际归还时间与验收结果作为固定字段写入请求体。
     */
    private ApiResponse returnRental(String admin, long rentalId, LocalDateTime actualReturnTime,
                                     String damageResult, Map<String, Object> extra) {
        // 复制额外字段。
        Map<String, Object> body = new HashMap<>(extra);
        // 写入实际归还时间。
        body.put("actualReturnTime", actualReturnTime.format(FORMAT));
        // 写入验收结果。
        body.put("damageResult", damageResult);
        // 调用归还接口。
        return call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return", admin, body);
    }

    // 费用流水用于核对逾期、赔偿与减免。
    /** 辅助方法：查询指定租赁单的全部费用流水。 */
    private List<RentalFee> fees(long rentalId) {
        // 按租赁单查询全部费用流水。
        return rentalFeeMapper.selectList(new LambdaQueryWrapper<RentalFee>()
                // 条件为租赁单 ID。
                .eq(RentalFee::getRentalId, rentalId));
    }

    // 支付流水用于核对押金、退款与应收。
    /** 辅助方法：查询指定租赁单的全部支付/退款/应收流水。 */
    private List<PaymentRecord> payments(long rentalId) {
        // 按租赁单查询全部支付流水。
        return paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                // 条件为租赁单 ID。
                .eq(PaymentRecord::getRentalId, rentalId));
    }

    /**
     * 汇总指定租赁单的 RECEIVABLE 应收金额。
     * 应收表示押金不足以覆盖费用、用户仍需补缴的部分。
     */
    private BigDecimal receivable(long rentalId) {
        // 从支付流水中筛选应收记录。
        return payments(rentalId).stream()
                // 只统计 RECEIVABLE 类型。
                .filter(p -> "RECEIVABLE".equals(p.getRecordType()))
                // 取出金额。
                .map(PaymentRecord::getAmount)
                // 累加为应收总额。
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 辅助方法：断言指定类型费用流水恰好一条且金额正确。
     * 同时防止重复记账导致金额翻倍。
     * 同时校验金额，防止重复记账导致金额翻倍。
     */
    private void assertFee(long rentalId, String type, String amount) {
        // 筛选指定类型的费用流水。
        List<RentalFee> matches = fees(rentalId).stream()
                // 类型匹配。
                .filter(f -> type.equals(f.getFeeType())).toList();
        // 必须有且只有一条。
        assertEquals(1, matches.size(), type + " 流水应只有一条");
        // 金额与期望值一致。
        assertEquals(0, matches.get(0).getAmount().compareTo(new BigDecimal(amount)));
    }

    /**
     * 辅助方法：断言指定类型支付流水恰好一条且金额正确。
     * 类型与金额同时校验，防止重复退款或漏记。
     */
    private void assertPayment(List<PaymentRecord> payments, String type, String amount) {
        // 筛选指定类型的支付流水。
        List<PaymentRecord> matches = payments.stream()
                // 类型匹配。
                .filter(p -> type.equals(p.getRecordType())).toList();
        // 必须有且只有一条。
        assertEquals(1, matches.size(), type + " 流水应只有一条");
        // 金额与期望值一致。
        assertEquals(0, matches.get(0).getAmount().compareTo(new BigDecimal(amount)));
    }

    /**
     * 辅助方法：双线程栅栏并发执行两个归还请求，尽量同时进入业务逻辑。
     * 超时后强制关闭线程池，避免测试挂起。
     * 两个线程都就绪后再统一放行，尽量制造真实竞争。
     */
    private List<ApiResponse> concurrently(Supplier<ApiResponse> first,
                                           Supplier<ApiResponse> second) throws Exception {
        // 固定两个线程的线程池。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // 就绪计数器：两个任务都准备完成后才能继续。
        CountDownLatch ready = new CountDownLatch(2);
        // 放行闸门：所有任务等待同一个信号。
        CountDownLatch go = new CountDownLatch(1);
        try {
            // 提交第一个请求。
            Future<ApiResponse> a = pool.submit(() -> await(ready, go, first));
            // 提交第二个请求。
            Future<ApiResponse> b = pool.submit(() -> await(ready, go, second));
            // 等待两个线程都就绪，超时则失败。
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            // 同时放行，尽量让两个请求并发进入业务逻辑。
            go.countDown();
            // 汇总两个请求的响应。
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            // 无论成功与否都关闭线程池，避免测试挂起。
            pool.shutdownNow();
        }
    }

    // 就绪后等待放行信号，再执行实际请求。
    /** 辅助方法：线程就绪后等待统一放行，再执行实际请求。 */
    private ApiResponse await(CountDownLatch ready, CountDownLatch go,
                              Supplier<ApiResponse> call) throws InterruptedException {
        // 标记当前线程已就绪。
        ready.countDown();
        // 等待主线程放行。
        go.await(10, TimeUnit.SECONDS);
        // 执行实际请求。
        return call.get();
    }

    // 档期固定为两天后，避开过去时间校验。
    /** 辅助方法：以两天后的档期创建租赁单并返回 ID。 */
    private long createRental(String userToken, long toolId) {
        // 调用用户端下单接口。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                // 指定工具。
                "toolId", toolId,
                // 开始时间为两天后。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 结束时间比开始晚一天。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人姓名。
                "pickupPerson", "王建国")));
        // 返回租赁单 ID。
        return created.get("data").get("id").asLong();
    }

    /**
     * 辅助方法：创建唯一名称、指定押金的工具并审核通过。
     * 工具名称带统一前缀，便于用例结束后的级联清理。
     * 押金金额由参数指定，用于覆盖封顶与应收边界。
     * 名称统一前缀便于用例结束后的级联清理。
     */
    private long createApprovedTool(String admin, String suffix, String deposit) {
        // 调用创建工具接口。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 名称前缀加后缀，保证唯一。
                "name", PREFIX + suffix,
                // 型号仅用于展示。
                "model", "测试型号",
                // 分类固定为维修类。
                "category", "WX",
                // 损坏等级为新。
                "damageLevel", "NEW",
                // 押金金额由参数决定。
                "deposit", new BigDecimal(deposit),
                // 存放位置为测试柜。
                "storageArea", "测试柜",
                // 使用说明为最小 HTML。
                "usageGuide", "<p>说明</p>",
                // 不附带图片。
                "imageUrls", List.of())));
        // 取出工具 ID。
        long id = created.get("data").get("id").asLong();
        // 审核通过。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        // 返回工具 ID。
        return id;
    }

    // 业务码用于区分参数错误、冲突与权限问题。
    /** 辅助方法：从响应体读取业务码。 */
    private String code(ApiResponse response) {
        // 从响应体读取 code 字段。
        return response.body().get("code").asString();
    }

    // 失败时输出完整响应，便于定位接口契约问题。
    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    private JsonNode ok(ApiResponse response) {
        // HTTP 状态必须为 200。
        assertEquals(200, response.status(), response.body().toString());
        // 业务码必须为 A200。
        assertEquals("A200", code(response), response.body().toString());
        // 返回响应体。
        return response.body();
    }

    // 管理员令牌用于创建工具、取件与归还。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        // 使用种子管理员账号登录。
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 用户令牌用于下单、评价与查询。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        // 使用种子用户手机号密码登录。
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    // 登录成功必须返回 tokenValue，供后续请求鉴权。
    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    private String login(String url, Map<String, Object> body) {
        // 调用登录接口。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        // 断言登录成功。
        assertEquals(200, response.status(), response.body().toString());
        // 取出令牌。
        return response.body().get("data").get("tokenValue").asString();
    }

    // 令牌为空时按匿名请求发送。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 构造请求方法与路径。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌时附加认证头。
        if (token != null) {
            // 使用 Bearer 方案。
            spec.header("Authorization", "Bearer " + token);
        }
        // 有请求体时设置 JSON 类型。
        if (body != null) {
            // 序列化并发送请求体。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 同步执行并解析响应。
        return spec.exchange((request, response) -> {
            // 确保响应流关闭。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取响应体。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 保留 HTTP 状态码。
                return new ApiResponse(response.getStatusCode().value(),
                        // 空响应按空对象处理。
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 读取失败转为非受检异常。
            } catch (IOException e) {
                // 保留原始原因。
                throw new IllegalStateException(e);
            }
        // 返回解析结果。
        });
    }
}
