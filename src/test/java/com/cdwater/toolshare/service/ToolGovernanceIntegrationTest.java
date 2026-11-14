// 工具治理集成测试：验证审核、状态机与索引同步等治理规则。
// 覆盖审核可见性、活动租赁与未完成报修守卫、旧版本更新拒绝、
// 报废恢复、向量索引同步、使用说明净化与审计日志。
// 测试通过随机端口访问真实 HTTP 接口，并直接调用 ToolService 做版本校验。
// 依赖测试数据库、内存对象存储与 MockitoSpyBean 索引观察器。
// 核心不变量是未审核工具不可见不可租、状态迁移必须留痕且同步索引。
// 每个用例按统一前缀清理工具、报修、租赁与日志。
// 文件内注释只说明治理语义与断言意图，不改变执行顺序。
package com.cdwater.toolshare.service;

// MyBatis-Plus 条件构造器用于按工具或前缀清理测试数据。
// 基类负责数据库迁移、容器生命周期与测试隔离。
// 内存存储配置保证完整应用上下文可以启动。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
// 报修与租赁实体用于构造状态守卫的前置数据。
// 两类数据都会阻止工具被错误地改为可用。
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
// 工具索引服务被 SpyBean 包裹，用于观察 upsert 与 remove。
// 工具服务提供底层状态迁移入口，用于模拟并发旧快照。
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolIndexService;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolService;
// ToolStatus 枚举与业务异常用于直接断言状态机守卫。
// BizException 表示旧版本迁移应被拒绝。
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.BizException;
// JUnit 生命周期注解负责准备数据与清理。
// Spring 测试注解启动完整上下文并注入真实依赖。
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
// HttpMethod 让通用请求方法支持 GET、POST、PATCH 等动作。
// MediaType 与 MockitoSpyBean 分别用于请求构造与索引观察。
// RestClient 负责访问真实 Web 层。
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 输入流与字符集工具用于读取原始响应体。
// 响应可能为空，需要安全地转换为空 JSON 对象。
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// 断言状态码、业务码、字段值、异常与日志内容。
// Mockito 匹配器与验证方法用于确认索引同步调用。
// 断言失败信息带上响应体，便于定位治理规则回归。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * 工具治理集成测试。
 * <p>
 * 测试范围：工具审核状态对可见性与下单的影响、工具状态机守卫、
 * 旧版本状态更新的并发保护、向量索引同步、报废恢复流程与知识库联动，
 * 以及使用说明的富文本净化与操作日志。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储；
 * ToolIndexService 使用 MockitoSpyBean 以便观察索引调用。
 * 关键不变量：未审核工具对用户不可见且不可下单；
 * 有待处理租赁或未完成报修时不能改为可用；报废为终态且恢复必须显式并说明原因；
 * 补充说明：审核状态决定工具是否进入用户可见范围。
 * 状态机守卫在改为可用时检查活动租赁与未完成报修。
 * 报废是终态，恢复必须走独立接口并填写原因。
 * 每次状态变化都要同步向量索引，保证 AI 检索一致。
 * 审计日志记录动作、前后状态、操作人与备注。
 * 富文本说明在写入前统一消毒，但保留安全标签。
 * 辅助方法封装登录、请求、日志查找与索引断言。
 * 测试数据按前缀管理，避免跨用例互相影响。
 * 每次状态变化都要同步向量索引并留下结构化审计日志。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolGovernanceIntegrationTest extends AbstractIntegrationTest {

    // 时间格式与接口入参保持一致，秒级精度避免截断差异。
    // 统一前缀用于识别并清理本类创建的工具及其关联数据。
    // 前缀同时出现在工具名称与部分业务描述中。
    // 清理只处理带前缀的数据，避免影响其它测试。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-GOV-";

    // 随机端口由 Spring Boot 注入，供 HTTP 客户端访问。
    // 随机端口避免并行测试时出现端口冲突。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    @Autowired
    // 工具 Mapper 用于准备工具、查询状态与清理。
    // 直接读取数据库可以绕过接口校验确认最终状态。
    /** 工具 Mapper，用于创建、查询与清理工具。 */
    ToolMapper toolMapper;

    @Autowired
    // 工具日志 Mapper 用于清理审计记录。
    // 日志先于工具删除，避免留下孤儿记录。
    /** 工具日志 Mapper，清理工具前先删除审计日志。 */
    ToolLogMapper toolLogMapper;

    @Autowired
    // 租赁 Mapper 用于构造活动租赁并修正档期。
    // 活动租赁是工具改为可用时的重要守卫条件。
    /** 租赁单 Mapper，用于构造活动租赁并清理。 */
    RentalMapper rentalMapper;

    @Autowired
    // 报修 Mapper 用于构造未完成报修并清理。
    // 未完成报修同样会阻止工具恢复可用。
    /** 报修 Mapper，用于构造未完成报修并清理。 */
    DamageReportMapper damageReportMapper;

    @Autowired
    // 工具服务提供版本化的状态迁移方法。
    // 直接调用可模拟携带旧快照的并发更新。
    /** 工具服务，用于直接调用版本化的状态迁移。 */
    ToolService toolService;

    @MockitoSpyBean
    // SpyBean 保留真实实现，同时允许验证索引调用。
    // 通过 clearInvocations 可以只统计本次状态变化的同步行为。
    /** 向量索引服务用 SpyBean 替换，验证状态变化时的索引同步。 */
    ToolIndexService toolIndexService;

    // HTTP 客户端在每个用例前绑定随机端口。
    // 它复用应用过滤器与鉴权，覆盖真实 Web 链路。
    // 客户端不保存会话，令牌通过请求头传递。
    RestClient client;
    // 树模型解析器用于读取业务码与 data 字段。
    // 响应为空时会被转换为空对象，避免解析异常。
    final ObjectMapper om = new ObjectMapper();

    // 统一响应结构，同时保留 HTTP 状态码与 JSON 响应体。
    // 便于断言 4xx、422 等非业务码驱动的错误语义。
    record ApiResponse(int status, JsonNode body) {
    }

    // 每个用例重新创建客户端，保证端口绑定正确。
    // 客户端构建成本低，无需跨用例复用。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // 使用随机端口构造基础地址。
        // 后续请求都通过该客户端发送，自动走真实 Web 层。
        // 这样鉴权、参数校验与异常处理都与生产一致。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按名称前缀清理测试工具及其报修、租赁、日志。
     * 工具日志先于工具删除，避免留下孤儿审计记录。
     * 清理顺序先报修、租赁、日志，再删工具本身。
     * 这样不会留下指向已删工具的关联记录。
     * 查询按名称前缀匹配，只处理本类创建的工具。
     * 清理在断言之后执行，失败时也不会阻断后续用例。
     * 日志先于工具删除，保持审计链路完整。
     */
    @AfterEach
    void cleanUp() {
        // 查询本类前缀创建的工具，逐个清理关联数据。
        // likeRight 生成前缀匹配，避免全表扫描后误删。
        // 工具名称前缀与创建方法保持一致。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                // 前缀匹配只命中本类工具。
                .likeRight(Tool::getName, PREFIX));
        // 逐个工具删除依赖数据，保证业务一致性。
        for (Tool tool : tools) {
            // 先删除该工具的报修单。
            // 报修引用工具与租赁，必须先于两者清理。
            // 条件按工具 ID 精确匹配。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 再删除该工具的租赁单。
            // 租赁引用工具，删除工具前必须清空。
            // 条件按工具 ID 精确匹配。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 然后删除工具审计日志。
            // 日志记录状态迁移，工具删除后不再保留。
            // 条件按工具 ID 精确匹配。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 关联数据清空后按主键删除工具。
            // 主键删除范围精确，避免误删其它测试数据。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：管理员新建工具后，用户尝试浏览与下单；随后管理员改为需修改、
     * 再审核通过，用户重新浏览并下单。
     * 前置条件：工具初始为待审核，用户端只能看到已通过的工具。
     * 断言目标：待审核与需修改阶段列表不返回、详情 404、下单被拒绝；
     * 需修改必须填写审核意见；通过后列表与详情可见且可下单；
     * 审计日志依次记录创建与两次审核，含操作人和备注，形成完整追溯链。
     * 补充说明：工具创建后初始为待审核，用户端不可见。
     * 待审核与需修改阶段，列表不返回、详情 404、下单被拒绝。
     * 需修改必须填写审核意见，否则服务端拒绝请求。
     * 审核通过后用户才能看到详情并创建预约。
     * 审计日志应记录创建与两次审核动作及操作人。
     * 日志中的前后状态构成完整追溯链。
     */
    @Test
    void audit_gates_user_visibility_and_booking() {
        // 分别获取管理员与用户令牌。
        // 管理员负责创建与审核，用户负责浏览与下单。
        // 两种身份覆盖审核可见性的双端行为。
        // 令牌在后续请求中复用。
        String admin = adminLogin();
        String user = userLogin();
        // 创建一个带安全说明的待审核工具。
        // 创建响应中的 auditStatus 应为待审核。
        // 工具名称按前缀生成，便于清理。
        // 创建动作本身会写入审计日志。
        JsonNode created = createTool(admin, "audit", "<p>正常说明</p>");
        long toolId = created.get("id").asLong();

        // 新工具审核状态应为 0，即待审核。
        // 状态名称与创建人字段用于验证返回结构。
        // 创建人应记录当前管理员。
        // 这些字段同时支撑管理端列表展示。
        assertEquals(0, created.get("auditStatus").asInt());
        assertEquals("待审核", created.get("auditStatusName").asString());
        assertEquals("admin", created.get("createdByName").asString());

        // 用户端按关键字查询，待审核工具不应出现在结果中。
        // 即使用户知道工具 ID，详情接口也应返回 404。
        // 可见性守卫必须同时作用于列表与详情。
        // 这是未审核工具不能流入用户侧的核心不变量。
        assertEquals(0, get("/api/user/tools?keyword=" + PREFIX + "audit", user)
                .get("data").get("total").asInt());
        assertCode("C404", get("/api/user/tools/" + toolId, user));

        // 用户尝试预约待审核工具，服务端应拒绝。
        // 返回 400 而不是业务成功码，说明审核守卫生效。
        // 错误信息应提示工具尚未审核。
        // 该断言覆盖下单入口的审核校验。
        ApiResponse booking = call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId));
        assertEquals(400, booking.status(), booking.body().toString());
        assertTrue(booking.body().get("message").asString().contains("审核"),
                booking.body().toString());

        // 管理员将工具改为需修改，但不填写审核意见。
        // 状态不允许缺少原因，服务端应返回 400。
        // 错误信息应提示必须填写审核意见。
        // 该规则保证审核意见可追溯。
        ApiResponse needsRemark = call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "NEEDS_CHANGE"));
        assertEquals(400, needsRemark.status(), needsRemark.body().toString());
        assertTrue(needsRemark.body().get("message").asString().contains("审核意见"));

        // 补充审核意见后，需修改状态应被接受。
        // auditStatus=2 表示需修改。
        // 状态名称与备注必须回显在响应中。
        // 用户端此时仍不可见该工具。
        JsonNode needsChange = ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "NEEDS_CHANGE", "remark", "请补充存放区域"))).get("data");
        assertEquals(2, needsChange.get("auditStatus").asInt());
        assertEquals("需修改", needsChange.get("auditStatusName").asString());
        assertEquals("请补充存放区域", needsChange.get("auditRemark").asString());
        assertCode("C404", get("/api/user/tools/" + toolId, user));

        // 管理员审核通过工具，状态变为已通过。
        // auditStatus=1 表示审核通过。
        // 备注记录通过原因，供审计检索。
        // 通过后工具进入用户可见范围。
        JsonNode approved = ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/audit",
                admin, Map.of("auditStatus", "APPROVED", "remark", "通过"))).get("data");
        assertEquals(1, approved.get("auditStatus").asInt());
        assertEquals("已通过", approved.get("auditStatusName").asString());

        // 审核通过后，用户端列表应能查到该工具。
        // 详情接口应返回 A200 而不是 404。
        // 可见性在两个接口上保持一致。
        assertEquals(1, get("/api/user/tools?keyword=" + PREFIX + "audit", user)
                .get("data").get("total").asInt());
        assertEquals("A200", get("/api/user/tools/" + toolId, user).get("code").asString());
        // 用户再次下单，此时审核守卫应放行。
        // 预约初始状态为待支付。
        // 该断言闭合审核通过后的可租链路。
        JsonNode rental = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId))).get("data");
        assertEquals("PENDING_PAYMENT", rental.get("status").asString());

        // 查询工具审计日志，验证动作与状态迁移。
        // 创建日志的来源状态为空，目标状态为可用。
        // 审核日志记录从需修改到已通过的迁移。
        // 日志按时间顺序返回，便于还原操作轨迹。
        JsonNode logs = get("/api/admin/tools/" + toolId + "/logs", admin).get("data");
        // 创建动作应被记录，目标是初始可用状态。
        // 来源状态为空表示这是生命周期起点。
        // 日志存在性证明创建路径写了审计记录。
        assertTrue(hasLog(logs, "CREATE", null, "AVAILABLE"), logs.toString());
        assertTrue(hasLog(logs, "AUDIT", "NEEDS_CHANGE", "APPROVED"), logs.toString());
        // 审核动作应记录前后状态。
        // 操作人应为执行审核的管理员。
        // 备注应保留审核时填写的通过原因。
        JsonNode auditLog = findLog(logs, "AUDIT", "NEEDS_CHANGE", "APPROVED");
        assertEquals("admin", auditLog.get("operatorName").asString());
        assertEquals("通过", auditLog.get("remark").asString());
    }

    /**
     * 场景：存在未结束租赁时把工具改为可用；另建带未完成报修的工具再尝试改为可用。
     * 前置条件：工具状态变更需要同时检查活动租赁与报修闭环。
     * 断言目标：两类操作均返回 400 并给出对应提示；带伤归还后工具保持维修中；
     * 报废后再改可用也因终态被拒绝，防止有争议的资产重新流入市场。
     * 补充说明：活动租赁与未完成报修是改为可用的两道守卫。
     * 第一道守卫在存在活动租赁时拒绝状态变更。
     * 第二道守卫在存在未完成报修时拒绝状态变更。
     * 带伤归还后工具应保持维修中，而不是直接可用。
     * 报废后再改可用会因终态被拒绝。
     * 这些规则防止有争议资产重新流入市场。
     */
    @Test
    void cannot_be_available_with_active_rental_or_unfinished_report() {
        // 获取管理员与用户令牌。
        // 用户负责创建租赁与报修，管理员负责归还与状态变更。
        // 双端身份覆盖两条守卫的触发路径。
        String admin = adminLogin();
        String user = userLogin();

        // 创建并审核一个工具，准备活动租赁场景。
        // 用户创建预约但不支付，租赁仍占用档期。
        // 此时把工具改为可用应被活动租赁守卫拒绝。
        long activeTool = createApprovedTool(admin, "active");
        ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(activeTool)));
        // 管理员尝试直接把工具改为可用。
        // 服务端应返回 400 并提示存在活动租赁。
        // 该守卫避免已占用工具被重复分配。
        // 活动租赁判断覆盖待支付与进行中的订单。
        ApiResponse guardedByRental = call(HttpMethod.PATCH,
                "/api/admin/tools/" + activeTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, guardedByRental.status(), guardedByRental.body().toString());
        assertTrue(guardedByRental.body().get("message").asString().contains("活动租赁"));

        // 另建一个工具，准备未完成报修场景。
        // 先创建租赁、支付并取件，再提交报修。
        // 报修未终结时工具不能被改为可用。
        long repairTool = createApprovedTool(admin, "repair");
        // 创建租赁并提取 ID。
        // 支付后管理员才能执行取件。
        // 取件后租赁进入使用中状态。
        // 这些步骤为报修提交准备合法前置状态。
        JsonNode rental = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(repairTool))).get("data");
        long rentalId = rental.get("id").asLong();
        ok(call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()));
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup", admin, Map.of()));
        // 用户提交报修，报修进入待处理状态。
        // 报修绑定当前租赁单与工具。
        // 图片列表为空，聚焦状态守卫。
        // 此时工具仍未进入维修中，需要带伤归还触发。
        ok(call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                "rentalId", rentalId, "damagePart", "电机", "description", "异响", "images", List.of())));
        // 调整租期到过去，使管理员可以执行归还。
        // 带伤归还结束后租赁线，但报修仍未完成。
        // 先把租期改到过去，使管理员可以执行带伤归还，工具随之进入维修中。
        // 带伤归还：租赁结束但报修尚未完成，工具转维修中
        // 读取租赁单并修改开始时间到过去。
        // 这样归还操作不会因未来档期被拒绝。
        // 直接通过 Mapper 修改，模拟租期自然结束。
        // 状态字段保持不变，仍处于使用中。
        Rental toReturn = rentalMapper.selectById(rentalId);
        toReturn.setStartTime(LocalDateTime.now().minusDays(1).withNano(0));
        rentalMapper.updateById(toReturn);
        // 管理员执行带伤归还，声明损坏严重。
        // 归还后租赁结束，工具应进入维修中。
        // 报修尚未完成，因此工具不能被改为可用。
        // 该操作是未完成报修守卫的触发前提。
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return", admin,
                Map.of("actualReturnTime", LocalDateTime.now().withNano(0).format(FORMAT),
                        "damageResult", "SEVERE", "returnWithDamage", true,
                        "remark", "带伤归还")));
        // 从数据库读取工具状态，确认带伤归还使其维修中。
        // 状态必须是 MAINTENANCE，而不是直接可用。
        // 该断言保证归还与报修两条线没有互相覆盖。
        assertEquals("MAINTENANCE", toolMapper.selectById(repairTool).getStatus(),
                "带伤归还时工具保持维修中");

        // 管理员尝试把维修中工具改为可用。
        // 服务端应返回 400 并提示存在未完成报修。
        // 该守卫避免维修未验收的工具重新出租。
        // 错误信息应明确指出拦截原因。
        ApiResponse guardedByReport = call(HttpMethod.PATCH,
                "/api/admin/tools/" + repairTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, guardedByReport.status(), guardedByReport.body().toString());
        assertTrue(guardedByReport.body().get("message").asString().contains("未完成报修"));

        // 管理员先把工具改为报废，进入终态。
        // 报废动作本身应被允许。
        // 随后再尝试改为可用应被终态守卫拒绝。
        // 该链路验证报废的不可逆性。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + repairTool + "/status",
                admin, Map.of("status", "SCRAPPED")));
        // 报废后再次尝试改为可用。
        // 服务端应返回 400 并提示工具已报废。
        // 终态不允许通过普通状态接口恢复。
        // 正确恢复必须走独立的恢复接口。
        ApiResponse scrapped = call(HttpMethod.PATCH,
                "/api/admin/tools/" + repairTool + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, scrapped.status(), scrapped.body().toString());
        assertTrue(scrapped.body().get("message").asString().contains("已报废"));
    }

    /**
     * 场景：先读取工具快照，再通过接口把它改为维修中，然后用旧快照执行报废迁移。
     * 前置条件：状态更新带版本号或状态前置校验。
     * 断言目标：旧版本迁移抛出业务异常，数据库仍为 MAINTENANCE，
     * 防止并发操作互相覆盖导致状态丢失。
     * 补充说明：工具快照读取后，另一个请求先完成了状态迁移。
     * 此时用旧快照执行迁移应被拒绝，而不是覆盖新状态。
     * 服务端通过版本或前置状态校验发现快照已过期。
     * 业务异常表示并发更新冲突。
     * 数据库最终状态仍应保持为维修中。
     * 该规则防止并发操作互相覆盖导致状态丢失。
     */
    @Test
    void stale_tool_state_update_is_rejected_instead_of_overwriting() {
        // 获取管理员令牌并创建已审核工具。
        // 工具将先被改为维修中，作为并发前的目标状态。
        // 已审核工具可以走普通状态接口。
        String admin = adminLogin();
        long toolId = createApprovedTool(admin, "version");
        // 读取工具快照，保存旧版本状态。
        // 这份快照在后续被用于携带过期信息执行迁移。
        // 快照与数据库当前状态将产生版本差异。
        Tool stale = toolService.getTool(toolId);

        // 通过接口把工具改为维修中。
        // 此时数据库状态更新，但旧快照仍是旧版本。
        // 下一步使用旧快照执行报废迁移。
        // 预期服务端检测到版本或状态不一致。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "MAINTENANCE")));

        // 使用旧快照执行报废迁移，应抛出业务异常。
        // 异常类型为 BizException，表示状态前置校验失败。
        // 迁移不会写入数据库，也不会同步索引。
        // 该断言覆盖并发冲突的拒绝路径。
        assertThrows(BizException.class, () -> toolService.transition(stale,
                ToolStatus.SCRAPPED, "TEST", "并发覆盖"));
        // 数据库状态仍应为维修中，旧快照未覆盖新状态。
        // 失败信息说明旧版本更新不能覆盖已提交的新状态。
        // 这是并发安全的关键不变量。
        assertEquals("MAINTENANCE", toolMapper.selectById(toolId).getStatus(),
                "旧版本状态更新不能覆盖已提交的新状态");
    }

    /**
     * 场景：工具改为维修中，再改为报废。
     * 前置条件：ToolIndexService 使用 SpyBean 可观察调用。
     * 断言目标：非删除状态触发 upsert 且携带正确工具；
     * 报废触发 remove，保证 AI 检索索引与业务状态一致，不推荐已报废工具。
     * 补充说明：非删除状态应触发索引 upsert。
     * 报废属于删除语义，应触发索引 remove。
     * SpyBean 允许验证真实调用，而不是替换实现。
     * clearInvocations 用于只统计本次状态变化。
     * 索引同步保证 AI 检索不会推荐失效工具。
     */
    @Test
    void status_change_keeps_vector_index_in_sync() {
        // 获取管理员令牌并创建已审核工具。
        // 工具先进入正常可用状态，再测试状态变化。
        // 索引服务已在上下文启动时完成初始同步。
        String admin = adminLogin();
        long toolId = createApprovedTool(admin, "index");

        // 清空之前的索引调用，只统计本次状态变更。
        // 创建与审核阶段也可能触发索引行为。
        // 清空之前的索引调用，只统计本次状态变更触发的同步行为。
        // 把工具改为维修中，触发非删除状态的索引更新。
        // 服务端应调用 upsert 并携带正确工具。
        // verify 确认 upsert 至少被调用一次。
        // 工具 ID 匹配避免把其它工具同步误判为本次结果。
        clearInvocations(toolIndexService);
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "MAINTENANCE")));
        // 断言索引 upsert 收到的是当前工具。
        // 工具非空且 ID 匹配，排除空对象或错对象。
        // 该验证直接绑定业务状态与检索索引。
        verify(toolIndexService).upsert(argThat(tool -> tool != null && tool.getId() == toolId));

        // 再次清空索引调用，准备验证报废同步。
        // 报废应走 remove 而不是 upsert。
        // 这样可以保证已报废工具从推荐候选消失。
        clearInvocations(toolIndexService);
        // 把工具改为报废，触发删除语义的索引同步。
        // 服务端应调用 remove 并传入工具 ID。
        // 该行为与状态机终态保持一致。
        // 若未 remove，AI 可能继续推荐报废工具。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "SCRAPPED")));
        // 断言 remove 使用当前工具 ID。
        // 索引删除按 ID 精确执行。
        // 该调用证明业务状态与检索索引同步。
        verify(toolIndexService).remove(toolId);
    }

    /**
     * 场景：工具报废后先尝试普通改状态，再调用恢复接口但不填原因，
     * 最后带原因恢复并检查日志与推荐结果。
     * 前置条件：报废是终态，恢复必须有独立入口和原因。
     * 断言目标：普通状态更新被拒绝；无原因返回 422；
     * 恢复后状态为可用、索引重新写入、日志记录 RESTORE 与原因；
     * AI 推荐重新出现该工具，证明恢复链路完整。
     * 补充说明：报废是终态，普通状态接口不能恢复。
     * 恢复必须走独立接口，并且必须填写原因。
     * 缺少原因时服务端返回 422，提示参数不完整。
     * 成功后工具恢复可用并重新写入索引。
     * 日志应记录 RESTORE 动作、前后状态与原因。
     * AI 推荐应重新出现该工具，证明恢复链路完整。
     */
    @Test
    void restore_is_explicit_requires_reason_and_reindexes() {
        // 获取管理员与用户令牌。
        // 管理员执行报废与恢复，用户验证推荐结果。
        // 双端身份覆盖恢复后的可见性。
        String admin = adminLogin();
        String user = userLogin();
        // 创建已审核工具并立即报废。
        // 工具进入终态后只能通过恢复接口返回。
        // 恢复前后都需要检查索引与日志。
        long toolId = createApprovedTool(admin, "restore");
        // 管理员通过普通状态接口报废工具。
        // 报废动作应成功并触发索引 remove。
        // 后续所有恢复尝试都以该终态为前提。
        // 该步骤不关心响应内容，只确认操作成功。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "SCRAPPED")));

        // 尝试用普通状态接口把报废工具改为可用。
        // 服务端应返回 400，因为报废是终态。
        // 错误信息应提示工具已报废。
        // 该守卫强制恢复走独立入口。
        ApiResponse normal = call(HttpMethod.PATCH,
                "/api/admin/tools/" + toolId + "/status", admin, Map.of("status", "AVAILABLE"));
        assertEquals(400, normal.status(), normal.body().toString());
        assertTrue(normal.body().get("message").asString().contains("已报废"),
                normal.body().toString());

        // 调用恢复接口但不填写原因。
        // 恢复必须可追溯，因此缺少原因应返回 422。
        // 422 表示请求格式合法但语义参数缺失。
        // 该断言保证恢复原因不被忽略。
        ApiResponse noReason = call(HttpMethod.POST,
                "/api/admin/tools/" + toolId + "/restore", admin, Map.of());
        assertEquals(422, noReason.status(), noReason.body().toString());

        // 清空索引调用，只统计恢复动作的同步。
        // 恢复成功后应重新 upsert 该工具。
        clearInvocations(toolIndexService);
        // 带原因调用恢复接口，工具应恢复可用。
        // 原因记录为误报废并说明核验结果。
        // 服务端应更新状态并写入恢复日志。
        // 恢复成功后工具重新进入可借范围。
        // 该调用覆盖完整恢复链路。
        JsonNode restored = ok(call(HttpMethod.POST,
                "/api/admin/tools/" + toolId + "/restore", admin,
                Map.of("reason", "误报废，经核验可继续使用"))).get("data");
        // 恢复后状态应为 AVAILABLE。
        // 状态名称应为可借用，与枚举映射一致。
        // 索引应重新 upsert 当前工具。
        assertEquals("AVAILABLE", restored.get("status").asString());
        assertEquals("可借用", restored.get("statusName").asString());
        verify(toolIndexService).upsert(argThat(tool -> tool != null && tool.getId() == toolId));

        // 查询工具日志，查找 RESTORE 动作。
        // 日志应记录从 SCRAPPED 到 AVAILABLE 的迁移。
        // 操作人应为执行恢复的管理员。
        // 备注应保留恢复原因。
        // 结构化日志让恢复动作可追溯。
        JsonNode restoreLog = findLog(get("/api/admin/tools/" + toolId + "/logs", admin).get("data"),
                "RESTORE", "SCRAPPED", "AVAILABLE");
        // 恢复日志必须存在，否则恢复行为无法审计。
        // findLog 按动作与前后状态精确匹配。
        // 断言失败信息会说明期望的迁移。
        assertNotNull(restoreLog, "恢复动作应写结构化日志");
        assertEquals("误报废，经核验可继续使用", restoreLog.get("remark").asString());
        assertEquals("admin", restoreLog.get("operatorName").asString());

        // 用户请求 AI 工具推荐，验证恢复后的可检索性。
        // 问题使用带前缀的工具名，聚焦当前工具。
        // limit=5 限制候选数量，减少无关结果。
        // 恢复可用后该工具应重新进入候选。
        JsonNode recommend = ok(call(HttpMethod.POST, "/api/user/ai/tool-recommend", user,
                Map.of("question", PREFIX + "restore", "limit", 5)));
        boolean recommended = false;
        // 遍历推荐候选，查找刚恢复的工具。
        // 只要出现一次即可证明索引已重新写入。
        // 未出现则说明恢复链路没有同步索引。
        for (JsonNode item : recommend.get("data").get("items")) {
            if (item.get("toolId").asLong() == toolId) {
                recommended = true;
            }
        }
        // 断言恢复工具出现在推荐候选中。
        // 失败信息会输出完整推荐结果，便于排查索引问题。
        // 该断言闭合恢复、索引与推荐三者的联动。
        assertTrue(recommended, "恢复可用后工具应重新进入推荐候选: " + recommend);
    }

    /**
     * 场景：报废工具仍存在待支付/活动租赁时尝试恢复。
     * 前置条件：下单会占用档期但尚未支付。
     * 断言目标：恢复返回 400 并提示活动租赁，避免把已占用工具恢复为可用。
     * 补充说明：工具先有一笔未支付预约，再被报废。
     * 未支付预约仍占用档期，属于活动租赁。
     * 恢复接口必须同时检查活动租赁。
     * 否则已占用工具会被错误恢复为可用。
     * 服务端应返回 400 并提示存在活动租赁。
     */
    @Test
    void restore_is_guarded_by_active_rental() {
        // 获取管理员与用户令牌。
        // 用户创建预约，管理员执行报废与恢复尝试。
        // 双端身份覆盖恢复守卫的触发路径。
        String admin = adminLogin();
        String user = userLogin();
        // 创建已审核工具，并由用户创建一笔预约。
        // 预约未支付，但仍属于活动租赁。
        // 随后管理员把工具报废。
        // 恢复时应被活动租赁守卫拦截。
        long toolId = createApprovedTool(admin, "restore-guard");
        ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId)));
        // 管理员报废工具，使其进入终态。
        // 报废本身不解除已有预约的占用。
        // 恢复接口需要重新检查活动租赁。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + toolId + "/status",
                admin, Map.of("status", "SCRAPPED")));

        // 尝试带原因恢复报废工具。
        // 服务端应返回 400，因为仍存在活动租赁。
        // 错误信息应提示活动租赁而非已报废。
        // 该守卫避免把已占用工具恢复为可用。
        ApiResponse guarded = call(HttpMethod.POST,
                "/api/admin/tools/" + toolId + "/restore", admin,
                Map.of("reason", "尝试恢复"));
        // 断言恢复被拒绝，状态码为 400。
        // 错误信息应包含活动租赁提示。
        // 失败信息带上完整响应，便于定位守卫分支。
        assertEquals(400, guarded.status(), guarded.body().toString());
        assertTrue(guarded.body().get("message").asString().contains("活动租赁"),
                guarded.body().toString());
    }

    /**
     * 场景：创建工具时提交含 script、事件属性与 javascript 链接的使用说明。
     * 前置条件：管理端写入与用户端展示前都经过统一富文本消毒。
     * 断言目标：危险标签、事件与协议全部移除；安全加粗与相对图片保留；
     * 工具仍为待审核且创建日志存在，说明消毒不破坏正常审核流程。
     * 补充说明：使用说明在写入前经过统一富文本消毒。
     * 危险标签、事件属性与 javascript 协议必须被移除。
     * 安全的加粗标签与相对图片地址应保留。
     * 消毒结果同时供管理端与用户端展示。
     * 工具仍保持待审核，说明消毒不破坏审核流程。
     */
    @Test
    void usage_guide_is_sanitized_but_safe_content_remains() {
        // 获取管理员令牌，创建带脏内容的工具。
        // 管理端写入与用户端展示共用同一消毒器。
        String admin = adminLogin();
        // 构造包含多种 XSS 载荷的使用说明。
        // onclick、script、javascript: 与 onerror 都应被清除。
        // strong 与相对图片地址属于安全内容，应保留。
        String dirty = """
                <p onclick="alert(1)">正常 <strong>图文</strong> 说明</p>
                <script>alert('xss')</script>
                <a href="javascript:alert(1)">坏链接</a>
                <img src="/api/files/guide.png" onerror="alert(1)" alt="示意图">
                """;
        // 创建工具并提取 ID。
        // 使用说明中的危险内容会在服务端消毒。
        // 创建后工具仍处于待审核状态。
        // 审核状态不因消毒而改变。
        long toolId = createTool(admin, "sanitize", dirty).get("id").asLong();

        // 查询管理端工具列表，读取消毒后的使用说明。
        // 关键字带统一前缀，只命中当前工具。
        // 结果中的 usageGuide 是最终入库内容。
        JsonNode rows = get("/api/admin/tools?keyword=" + PREFIX + "sanitize", admin)
                .get("data").get("records");
        // 断言查询只返回一条记录。
        // 记录数量异常可能意味着前缀匹配到了其它数据。
        // 随后从记录中提取使用说明。
        assertEquals(1, rows.size(), rows.toString());
        String guide = rows.get(0).get("usageGuide").asString();

        // 危险标签、事件属性与协议必须全部消失。
        // script 标签会执行任意脚本。
        // onclick 与 onerror 会触发内联事件。
        // javascript: 链接会诱导用户执行脚本。
        // 四个断言共同覆盖常见 XSS 载荷。
        assertFalse(guide.toLowerCase().contains("<script"), guide);
        assertFalse(guide.toLowerCase().contains("onclick"), guide);
        assertFalse(guide.toLowerCase().contains("javascript:"), guide);
        assertFalse(guide.toLowerCase().contains("onerror"), guide);
        // 安全的加粗标签应被保留。
        // 相对图片地址应被保留，且不能带事件属性。
        // 消毒器只移除危险内容，不破坏正常格式。
        // 工具审核状态仍为待审核。
        assertTrue(guide.contains("<strong>图文</strong>"), guide);
        assertTrue(guide.contains("src=\"/api/files/guide.png\""), guide);
        assertEquals("待审核", rows.get(0).get("auditStatusName").asString());

        // 查询工具日志，确认创建动作已记录。
        // 消毒发生在创建链路中，不应阻断日志写入。
        // 日志目标是初始可用状态。
        JsonNode logs = get("/api/admin/tools/" + toolId + "/logs", admin).get("data");
        // 创建日志应存在且来源状态为空。
        // 该断言证明消毒不破坏审计流程。
        assertTrue(hasLog(logs, "CREATE", null, "AVAILABLE"), logs.toString());
    }

    /**
     * 辅助方法：创建工具并立即审核通过，返回工具 ID。
     * 供只关心后续状态与索引行为的用例快速准备数据。
     * 供只关心后续状态与索引行为的用例快速准备数据。
     * 内部先创建工具，再调用审核接口通过。
     * 返回工具 ID，便于后续状态迁移与清理。
     * 审核备注与创建说明使用最小安全内容。
     */
    private long createApprovedTool(String admin, String suffix) {
        // 先创建带安全说明的工具并提取 ID。
        // 审核通过后工具才允许状态迁移与预订。
        long id = createTool(admin, suffix, "<p>说明</p>").get("id").asLong();
        // 调用管理员审核接口通过工具。
        // 审核动作会写入审计日志。
        // 通过后工具进入用户可见范围。
        // 返回 ID 供调用方继续操作。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")));
        return id;
    }

    /**
     * 辅助方法：以统一前缀与指定使用说明创建工具，返回创建响应中的 data 节点。
     * 调用方负责断言业务码并提取 ID。
     */
    private JsonNode createTool(String admin, String suffix, String usageGuide) {
        // 调用管理员创建工具接口，返回 data 节点。
        // 名称带统一前缀，便于后续查询与清理。
        // 分类固定为 WX，与编号规则一致。
        // 使用说明由调用方传入，用于净化测试。
        // 图片列表为空，聚焦文本治理。
        return ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                // 型号、分类与损坏等级取固定测试值。
                // 这些字段不参与治理规则断言。
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                // 押金固定 100 元，满足实体非空约束。
                // 存放区域与使用说明带最小内容。
                "deposit", 100,
                "storageArea", "测试柜",
                // 使用说明可能包含待消毒的富文本。
                // 图片列表为空，避免上传链路干扰。
                "usageGuide", usageGuide,
                "imageUrls", List.of()))).get("data");
    }

    // 构造两天后开始、租期一天的预约请求体。
    // 档期选择避免与库内既有租赁冲突。
    /** 辅助方法：构造两天后开始、租期一天的预约请求体。 */
    private Map<String, Object> booking(long toolId) {
        // 返回 Map 形式的 JSON 请求体。
        // 工具 ID 由调用方传入。
        // 开始与结束时间保持秒级精度。
        // 取件人使用固定测试姓名。
        return Map.of(
                // 绑定目标工具。
                "toolId", toolId,
                // 档期从两天后开始，降低冲突概率。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 结束时间比开始晚一天。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人字段满足接口校验。
                "pickupPerson", "王建国");
    }

    // 判断日志列表中是否存在指定动作与状态迁移。
    // 内部委托给 findLog 完成精确匹配。
    /** 辅助方法：判断日志列表中是否存在指定动作与状态迁移。 */
    private boolean hasLog(JsonNode logs, String action, String from, String to) {
        // 只要找到一条匹配日志即视为存在。
        // 来源或目标状态由调用方决定是否为空。
        return findLog(logs, action, from, to) != null;
    }

    /**
     * 辅助方法：按动作、来源状态与目标状态查找审计日志。
     * 允许来源或目标为空，以匹配创建等没有起始状态的日志。
     * 按动作、来源状态与目标状态查找审计日志。
     * 来源或目标为空时匹配没有该状态的日志。
     * 创建动作通常没有来源状态。
     * 找不到返回 null，供调用方断言不存在。
     * 该辅助方法避免重复遍历日志数组。
     */
    private JsonNode findLog(JsonNode logs, String action, String from, String to) {
        // 遍历日志数组，逐条比较动作与状态。
        // 找到第一条匹配记录即返回。
        // 日志顺序按时间排列，第一条即最早匹配项。
        for (JsonNode log : logs) {
            // 动作必须完全一致。
            // 动作名如 CREATE、AUDIT、RESTORE。
            // 动作不匹配时直接跳过当前记录。
            boolean sameAction = action.equals(log.get("action").asString());
            JsonNode fromNode = log.get("fromStatus");
            JsonNode toNode = log.get("toStatus");
            // 来源状态为空时，接受字段缺失或 JSON null。
            // 来源状态非空时，要求字段存在且完全一致。
            // 这样同一方法可同时匹配创建与状态迁移日志。
            // 空值分支避免创建日志因没有来源状态而漏匹配。
            boolean sameFrom = from == null
                    ? fromNode == null || fromNode.isNull()
                    : fromNode != null && from.equals(fromNode.asString());
            boolean sameTo = to == null
                    // 目标状态的匹配规则与来源状态一致。
                    // 目标为空时接受缺失或 null。
                    // 目标非空时要求字段存在且完全一致。
                    // 目标状态通常代表迁移后的工具状态。
                    ? toNode == null || toNode.isNull()
                    : toNode != null && to.equals(toNode.asString());
            if (sameAction && sameFrom && sameTo) {
                // 三个条件同时满足才算一次匹配。
                // 命中后返回当前日志节点。
                // 调用方可以继续读取操作人与备注字段。
                return log;
            }
        }
        // 遍历结束仍未找到匹配日志，返回 null。
        // 调用方据此断言日志不存在或触发兜底逻辑。
        return null;
    }

    // 断言 HTTP 200 与业务码 A200，并返回响应体。
    // 供成功路径的用例减少重复断言。
    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    private JsonNode ok(ApiResponse response) {
        // 先断言 HTTP 状态为 200。
        // 失败信息包含完整响应体。
        // 再断言业务码为 A200。
        assertEquals(200, response.status(), response.body().toString());
        // 业务码断言与状态码分开，便于区分传输层与业务层错误。
        // 返回 body 供调用方读取 data。
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    // 只断言响应体中的业务码。
    // 适用于查询类或预期失败类请求。
    /** 辅助方法：只断言响应体中的业务码，适用于查询类请求。 */
    private void assertCode(String code, JsonNode body) {
        // 业务码不匹配时输出完整响应。
        // 调用方决定期望码，如 C404 或 C401。
        assertEquals(code, body.get("code").asString(), body.toString());
    }

    // 发送 GET 请求并直接返回响应体。
    // 内部复用通用 call 方法。
    /** 辅助方法：发送 GET 请求并直接返回响应体。 */
    private JsonNode get(String url, String token) {
        // GET 请求没有请求体，令牌按需携带。
        // 返回的 JSON 供调用方断言业务码与数据。
        return call(HttpMethod.GET, url, token, null).body();
    }

    // 管理员账号登录并返回令牌。
    // 使用种子管理员用户名与密码。
    /** 辅助方法：管理员账号登录并返回令牌。 */
    private String adminLogin() {
        // 委托给通用登录方法，指定管理员登录地址。
        // 管理端使用 username 字段。
        return login("admin", "Aa123456", "/api/admin/login");
    }

    // 种子用户密码登录并返回令牌。
    // 用户端使用手机号字段。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        // 委托给通用登录方法，指定用户登录地址。
        // 手机号与密码与种子数据一致。
        return login("13800000001", "Aa123456", "/api/user/login/by-pwd");
    }

    /**
     * 辅助方法：按 URL 判断账号字段是 username 还是 phone，调用登录接口并取令牌。
     * 同时兼容管理端与用户端的登录契约。
     * 按 URL 判断账号字段是 username 还是 phone。
     * 管理端与用户端的登录契约不同。
     * 登录成功返回 tokenValue。
     * 失败信息包含完整响应。
     */
    private String login(String username, String password, String url) {
        // 根据地址中是否包含 /admin/ 选择账号字段名。
        // 该判断让两个登录接口共用同一辅助方法。
        // 字段名错误会导致参数校验失败。
        String accountField = url.contains("/admin/") ? "username" : "phone";
        // 调用登录接口并传入账号与密码。
        // 管理员与用户都返回相同的 tokenValue 结构。
        // 响应同时保留 HTTP 状态码与业务码。
        ApiResponse resp = call(HttpMethod.POST, url, null,
                Map.of(accountField, username, "password", password));
        // 断言登录 HTTP 状态为 200。
        // 失败信息包含响应体，便于排查种子数据问题。
        // 成功后从 data 中提取令牌。
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }

    // 发送可带令牌的 JSON 请求，返回状态码与响应体。
    // 支持任意 HTTP 方法，供治理用例复用。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 按传入方法构造请求并绑定目标地址。
        // 令牌与请求体都按需添加。
        // 这样 GET、POST、PATCH 等方法可以共用同一入口。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌时添加 Bearer 鉴权头。
        // 匿名请求用于验证未登录行为。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 有请求体时设置 JSON 内容类型。
        // GET 与部分 POST 请求可以没有请求体。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 使用 exchange 读取响应，保留状态码与原始字节。
        // 这样 4xx、422 等响应不会被当作异常抛出。
        // 读取逻辑在回调中完成。
        // 便于统一断言业务码与 HTTP 状态。
        return spec.exchange((request, response) -> {
            // 使用 try-with-resources 安全关闭响应体。
            // 读取全部字节后按 UTF-8 解码。
            try (InputStream in = response.getBody()) {
                // 将响应字节转换为字符串。
                // 字符集与接口实际输出保持一致。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 空响应回退为空对象，避免解析失败。
                // 返回结构同时包含状态码与 JSON 树。
                // 该结构适配非业务码驱动的错误断言。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // IO 异常包装为运行时异常，便于测试直接失败。
            // 不吞掉读取错误，保证问题可定位。
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
