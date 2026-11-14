package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
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

/*
 * 阅读顺序建议
 * ------------
 * 先阅读“场景清单与失败后果”了解每个用例保护的业务风险，
 * 再阅读“测试设计说明”了解状态机、并发与通知约束，
 * 最后按用例中的步骤注释查看具体数据准备与断言。
 * 辅助方法集中在文件后半部分，
 * 它们只负责造数与请求，不包含业务断言。
 * 若新增报修状态或工具状态，
 * 应同时补充场景清单与状态机用例，
 * 避免文档与测试覆盖脱节。
 */
/*
 * 测试设计说明
 * ------------
 * 一、被测能力：
 *     报修单从提交、核验、维修到验收的完整状态机，
 *     以及验收结果与工具目标状态的组合约束。
 *
 * 二、报修状态流转：
 *     SUBMITTED -> VERIFIED -> REPAIRING -> COMPLETED。
 *     核验为误报时进入 REJECTED，
 *     验收为不合格且工具报废时进入 SCRAPPED。
 *     未核验前不能更新维修进度，也不能验收；
 *     终态后不能再次流转。
 *
 * 三、工具状态约束：
 *     验收请求携带 toolStatusAfter，表示希望把工具置为何种状态。
 *     但工具能否恢复可用还取决于租赁是否结束：
 *     租赁仍在租出中时，即使验收合格也不能把工具重新上架。
 *
 * 四、并发约束：
 *     同一租赁单最多允许一张活动报修。
 *     该约束由数据库唯一索引与服务层校验共同保证，
 *     并发创建时只能有一个请求成功。
 *
 * 五、通知约束：
 *     维修进度更新与维修完成需要通知居民；
 *     维修完成还需要通知工作人员，并带上报修单号，
 *     保证维修闭环对双方可见。
 *
 * 六、依赖环境：
 *     随机端口完整应用、测试数据库、内存对象存储与种子账号。
 *     租借、支付、取件走真实接口，报修断言走真实数据库。
 *
 * 七、数据清理：
 *     按工具名称前缀定位本类数据，
 *     依次删除报修、租赁、工具日志与工具，
 *     删除顺序从业务子表到主表，避免残留。
 *
 * 八、断言策略：
 *     状态机错误同时断言业务码 C409 与 HTTP 409；
 *     参数组合错误断言 C400；
 *     成功路径统一断言 200/A200，并回查数据库确认落库状态。
 *
 * 九、时间处理：
 *     预约时间取两天后并去除纳秒，
 *     避免与种子数据档期冲突，也避免序列化精度差异。
 *
 * 十、失败定位：
 *     断言消息中尽量带上响应体或路径，
 *     出现回归时可以快速判断是状态机、权限还是数据准备问题。
 *
 * 十一、测试隔离：
 *     每个用例创建独立工具与租赁，
 *     不依赖其他用例的执行顺序，也不共享可变状态。
 *
 * 十二、覆盖缺口说明：
 *     短信等外部渠道由消息模块的测试覆盖，
 *     本类只验证消息是否按接收者与内容落库。
 */
/*
 * 场景清单与失败后果
 * ------------------
 * 1. illegal_transitions_return_409
 *    覆盖未核验更新进度、未核验验收、重复核验、
 *    维修未完成验收，以及终态后的再次流转。
 *    失败后果：状态机被绕过，资产与费用数据错乱。
 *
 * 2. false_report_is_rejected_without_touching_tool
 *    覆盖误报核验。报修单应关闭，但工具状态不能被误改。
 *    失败后果：正常工具被误送修，影响其他用户租借。
 *
 * 3. old_report_cannot_touch_a_returned_or_newly_rented_tool
 *    覆盖租赁已结束、工具已被新租约占用时的旧工单。
 *    失败后果：历史工单影响当前在租工具。
 *
 * 4. concurrent_create_only_one_active_report_succeeds
 *    覆盖同一租赁单的并发报修创建。
 *    失败后果：并发下产生多张活动工单，维修与费用无法对账。
 *
 * 5. repair_completed_notifies_resident_and_staff
 *    覆盖维修完成后的居民通知与管理端通知。
 *    失败后果：用户不知道维修结果，工作人员无法追踪工单。
 *
 * 6. acceptance_combines_result_with_tool_target_status
 *    覆盖验收结果与工具目标状态的非法组合。
 *    失败后果：缺陷工具被错误上架，造成安全隐患。
 *
 * 7. qualified_accept_keeps_tool_maintenance_while_rental_is_active
 *    覆盖租赁未结束时验收合格。工具不得恢复可用。
 *    失败后果：在租工具被重复出租，产生档期冲突。
 *
 * 8. qualified_accept_makes_tool_available_after_rental_ended
 *    覆盖租赁结束后验收合格。工具应恢复可用。
 *    失败后果：合格工具长期停留在维修中，资产利用率下降。
 *
 * 9. scrapped_is_terminal_and_cannot_be_reopened
 *    覆盖报废验收的终态性。
 *    失败后果：已报废资产被重新打开，资产账实不符。
 *
 * 辅助方法与上述场景共享同一套登录、建工具、造租赁流程，
 * 每个场景只改变与断言目标相关的数据，
 * 从而避免测试之间出现隐式耦合。
 */
/**
 * 报修流程集成测试。
 * <p>
 * 测试范围：报修单状态机、核验、维修进度、验收结果与工具目标状态的组合约束、
 * 活动报修唯一性、维修完成通知，以及过期报修对已归还/重新出租工具的隔离。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子账号。
 * 关键不变量：未核验不能更新进度或验收；终态不可再流转；
 * 同一租赁单最多一张活动报修，并发创建只成功一次；
 * 部分/不合格验收不能把工具置为可用；工具恢复可用还需租赁已结束。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class DamageWorkflowIntegrationTest extends AbstractIntegrationTest {

    // 统一的时间序列化格式，与预约接口期望的 LocalDateTime 入参一致。
    // 固定到秒可以消除纳秒差异造成的字符串比较噪声。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 测试工具名称前缀，用于识别和清理本类创建的工具及关联数据。
    private static final String PREFIX = "IT-DMG-";

    // 随机端口由 Spring Boot 启动时分配，
    // 测试客户端据此访问真实 HTTP 接口。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 工具 Mapper：创建测试工具，并在断言中读取工具状态。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：清理工具前先删除关联日志。
    /** 工具日志 Mapper，清理工具前先删日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;
    // 租赁单 Mapper：准备报修前置租赁，并在过期工单场景中调整状态。
    /** 租赁单 Mapper，用于准备报修前置租赁。 */
    @Autowired
    RentalMapper rentalMapper;
    // 报修 Mapper：直接读取报修单当前状态，
    // 避免只依赖接口响应而看不到真实落库结果。
    /** 报修 Mapper，用于创建、断言与清理报修单。 */
    @Autowired
    DamageReportMapper damageReportMapper;
    // 消息 Mapper：验证维修完成通知是否送达居民与工作人员。
    /** 消息 Mapper，用于验证维修完成通知。 */
    @Autowired
    MessageMapper messageMapper;

    // HTTP 客户端在 @BeforeEach 中按随机端口创建。
    RestClient client;
    // JSON 解析器与客户端配合，把响应体转换为可断言的树模型。
    final ObjectMapper om = new ObjectMapper();

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

    // 用例结束后按工具名称前缀清理报修、租赁、日志与工具。
    // 删除顺序从业务子表到主表，避免外键或残留数据。
    /**
     * 用例结束后按工具名称前缀清理报修、租赁、日志与工具，
     * 删除顺序从业务子表到主表，避免残留脏数据。
     */
    @AfterEach
    void cleanUp() {
        // 先查出本类创建的全部测试工具。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                // 按名称前缀筛选，避免误删种子工具或其他测试的数据。
                .likeRight(Tool::getName, PREFIX));
        // 逐个清理工具关联的业务数据。
        for (Tool tool : tools) {
            // 先删报修单，报修引用租赁与工具。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 再删租赁单，租赁引用工具与用户。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 删除工具日志，日志引用工具。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 最后删除工具本体。
            toolMapper.deleteById(tool.getId());
        }
    }

    // 覆盖点：报修状态机的非法流转防护。
    // 业务背景：报修流程涉及工具资产与用户押金，
    //     越级操作可能让未核验的工单直接完成或让终态工单复活。
    // 前置条件：报修单初始状态为 SUBMITTED。
    // 验证路径：
    //     1) 未核验时尝试更新进度与验收；
    //     2) 核验后尝试重复核验；
    //     3) 维修未完成时尝试验收；
    //     4) 正常完成后尝试再次验收、维修、核验。
    // 断言目标：所有非法操作返回 C409 且 HTTP 409，
    //     失败操作不改变报修单状态。
    // 失败后果：状态机形同虚设，资产与费用数据可能错乱。
    // 该用例是报修模块最核心的回归。
    /**
     * 场景：在报修生命周期各阶段尝试越级或重复操作。
     * 前置条件：报修单初始为 SUBMITTED。
     * 断言目标：未核验时更新进度/验收、重复核验、维修未完成验收、
     * 终态后的再次验收/维修/核验全部返回 C409，
     * 且任何失败操作都不改变报修单状态。
     */
    @Test
    void illegal_transitions_return_409() {
        // 管理员登录，用于核验、进度与验收操作。
        String admin = adminLogin();
        // 种子用户登录，用于提交报修。
        String user = userLogin();
        // 创建一台已审核工具。
        long toolId = createApprovedTool(admin, "illegal");
        // 通过预约、支付、取件构造可报修的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修，得到报修单 ID。
        long reportId = createReport(user, rentalId);

        // 核验前的两道门禁先验证：
        // 未核验时既不能推进维修，也不能直接验收。
        // 未核验前不允许更新维修进度或执行验收，先验证两道门禁。
        // 未核验不可更新进度 / 验收
        // 未核验不可更新维修进度。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "开始维修")));
        // 未核验不可执行验收。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        // 管理员核验通过，并指定维修人。
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED", "repairPerson", "王师傅")));
        // 核验成功后，重复核验必须被拒绝，
        // 否则维修人与核验结果可能被覆盖。
        // 核验是一次性动作，重复核验应被状态机拒绝。
        // 不可重复核验
        // 核验是一次性动作，重复核验应被状态机拒绝。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        // 维修进度尚未完成时，验收必须被拒绝，
        // 避免工单在未实际维修的情况下关闭。
        // 维修进度尚未完成时，即使核验通过也不能验收。
        // 维修未完成不可验收
        // 维修进度尚未完成时，即使核验通过也不能验收。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        // 把维修进度推进到完成。
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));
        // 维修完成后执行合格验收。
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));
        // 报修单应进入 COMPLETED。
        assertEquals("COMPLETED", report(reportId).getStatus());
        // 验收完成后进入终态，
        // 后续验收、维修、核验都不应再成功。
        // 验收完成后报修进入终态，后续任何操作都应返回冲突。
        // 终态不可再流转
        // 验收完成后报修进入终态，后续任何操作都应返回冲突。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "MAINTENANCE")));
        // 终态后不能再次更新维修进度。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "重复维修")));
        // 终态后不能再次核验。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "FALSE")));
    }

    // 覆盖点：误报核验对工具状态的影响。
    // 业务背景：用户可能误报故障，
    //     管理员核验为 FALSE 后工单应关闭，但不能误改工具状态。
    // 前置条件：工具仍处于租出中，报修单为 SUBMITTED。
    // 验证路径：核验为 FALSE -> 读取报修单与工具状态 ->
    //     再尝试更新维修进度。
    // 断言目标：报修单 REJECTED；工具保持 RENTED；
    //     后续进度更新返回 C409。
    // 失败后果：误报把正常工具送修，影响其他用户租借。
    // 该用例验证“拒绝报修”不等于“修改工具”。
    /**
     * 场景：管理员核验后判定为误报。
     * 前置条件：工具仍处于租出中。
     * 断言目标：报修单状态变为 REJECTED；工具保持 RENTED；
     * 误报后不能继续更新维修进度，避免无效工单进入维修流程。
     */
    @Test
    void false_report_is_rejected_without_touching_tool() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "false");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);

        // 管理员核验为误报，并填写备注。
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "FALSE", "remark", "用户误报")));

        // 报修单应进入 REJECTED 终态。
        assertEquals("REJECTED", report(reportId).getStatus());
        // 工具仍应保持租出中，不因误报被送修。
        assertEquals("RENTED", toolMapper.selectById(toolId).getStatus());
        // 误报后不能继续更新维修进度。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "不应处理")));
    }

    // 覆盖点：过期工单隔离。
    // 业务背景：报修提交后租赁可能已经结束，
    //     工具也可能被新的租赁占用。
    //     此时旧工单不能把工具错误送修或改状态。
    // 前置条件：报修仍为 SUBMITTED，但对应租赁已 RETURNED。
    // 验证路径：把租赁改为 RETURNED、工具改为 RENTED ->
    //     再核验旧报修。
    // 断言目标：核验返回 C409；工具保持 RENTED；
    //     报修单保持 SUBMITTED。
    // 失败后果：历史工单可影响当前正在出租的工具。
    // 该用例验证工单与租赁生命周期的一致性。
    /**
     * 场景：租赁已归还且工具已被新的租赁占用时，处理一份旧报修。
     * 前置条件：报修仍为 SUBMITTED，但对应租赁已经结束。
     * 断言目标：核验返回 C409；工具状态保持 RENTED；报修单保持 SUBMITTED，
     * 防止旧工单把正在出租的工具错误送修或改状态。
     */
    @Test
    void old_report_cannot_touch_a_returned_or_newly_rented_tool() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "stale");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);

        // 构造旧报修与已结束租赁并存的状态，验证过期工单不能改动在租工具。
        // 模拟租赁已归还且工具已被新租赁占用
        // 模拟租赁已归还。
        Rental returned = rentalMapper.selectById(rentalId);
        // 状态改为 RETURNED，表示租赁生命周期结束。
        returned.setStatus("RETURNED");
        // 更新租赁状态。
        rentalMapper.updateById(returned);
        // 模拟工具已被新的租赁占用。
        Tool tool = toolMapper.selectById(toolId);
        // 工具当前仍在租出中。
        tool.setStatus("RENTED");
        // 更新工具状态。
        toolMapper.updateById(tool);

        // 旧报修此时不应再能核验成功。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        // 工具状态不能被旧工单改动。
        assertEquals("RENTED", toolMapper.selectById(toolId).getStatus());
        // 报修单保持在提交状态，等待后续人工处理。
        assertEquals("SUBMITTED", report(reportId).getStatus());
    }

    // 覆盖点：并发创建活动报修的唯一性。
    // 业务背景：同一租赁单只允许一张活动报修，
    //     否则会出现重复维修、重复计费。
    // 前置条件：同一租赁单、同一用户、相同请求体。
    // 验证路径：两个线程通过栅栏同时提交报修。
    // 断言目标：一个 A200、一个 C409；
    //     数据库中活动报修只有一条。
    // 失败后果：并发下创建多张工单，维修与费用无法对账。
    // 该用例同时验证数据库唯一约束与服务层异常映射。
    /**
     * 场景：两个线程同时对同一租赁单提交报修。
     * 前置条件：同一租赁单只允许一张活动报修。
     * 断言目标：一个请求成功、一个返回 C409；数据库中活动报修只有一条，
     * 验证数据库唯一约束与并发防护共同生效。
     */
    @Test
    void concurrent_create_only_one_active_report_succeeds() throws Exception {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "concurrent");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 固定报修请求体，保证两个线程提交完全相同的业务数据。
        Map<String, Object> body = Map.of(
                "rentalId", rentalId,
                "damagePart", "电机",
                "description", "异响",
                "images", List.of());

        // 两个报修请求同时放行，验证唯一活动报修约束在并发下仍成立。
        List<ApiResponse> results = concurrently(
                () -> call(HttpMethod.POST, "/api/user/damage-reports", user, body),
                () -> call(HttpMethod.POST, "/api/user/damage-reports", user, body));

        // 统计成功响应数量。
        long success = results.stream().filter(r -> "A200".equals(code(r))).count();
        // 统计冲突响应数量。
        long conflict = results.stream().filter(r -> "C409".equals(code(r))).count();
        // 只能有一个请求成功。
        assertEquals(1, success, results.toString());
        // 另一个请求必须收到状态冲突。
        assertEquals(1, conflict, results.toString());
        // 数据库中活动报修只能有一条。
        assertEquals(1, damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rentalId)
                .in(DamageReport::getStatus, List.of("SUBMITTED", "VERIFIED", "REPAIRING"))));
    }

    // 覆盖点：维修完成后的双向通知。
    // 业务背景：维修进度与结果需要让居民知情，
    //     工作人员也需要一条带报修单号的通知便于归档。
    // 前置条件：报修已核验并推进到维修完成。
    // 验证路径：核验 -> REPAIRING -> COMPLETED ->
    //     按接收者、标题与内容查询消息表。
    // 断言目标：居民收到“维修进度更新”且内容包含“维修完成”；
    //     管理端收到“维修已完成”且内容包含报修单号。
    // 失败后果：用户不知道维修结果，工作人员无法追踪工单。
    // 消息按接收者与标题过滤，避免其他通知干扰断言。
    /**
     * 场景：核验通过并把维修进度推进到完成。
     * 前置条件：报修关联居民用户与工具。
     * 断言目标：居民收到“维修进度更新/维修完成”消息；
     * 管理端收到含报修单号的“维修已完成”消息，
     * 保证维修闭环对双方可见。
     */
    @Test
    void repair_completed_notifies_resident_and_staff() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "notify");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);
        // 管理员核验为疑似故障。
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "SUSPECTED")));
        // 进入维修中。
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "REPAIRING", "progressNote", "维修中")));
        // 推进到维修完成，触发完成通知。
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));

        // 读取报修单，取得用户 ID 与报修单号。
        DamageReport report = report(reportId);
        // 报修单号会出现在管理端通知内容中。
        String reportNo = report.getReportNo();
        // 居民通知：接收者类型 USER、接收者 ID 为报修用户，
        // 标题为“维修进度更新”，内容包含“维修完成”。
        assertTrue(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, "USER")
                .eq(Message::getReceiverId, report.getUserId())
                .eq(Message::getTitle, "维修进度更新")
                .like(Message::getContent, "维修完成")) >= 1, "居民应收到维修完成通知");
        // 管理端通知：接收者类型 ADMIN，
        // 标题为“维修已完成”，内容包含报修单号。
        assertTrue(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, "ADMIN")
                .eq(Message::getTitle, "维修已完成")
                .like(Message::getContent, reportNo)) >= 1, "工作人员应收到维修完成通知");
    }

    // 覆盖点：验收结果与工具目标状态的组合校验。
    // 业务背景：不合格或部分合格的验收不能直接把工具置为可用，
    //     否则存在缺陷的工具会重新流入租借市场。
    // 前置条件：报修已核验并推进到维修完成。
    // 验证路径：不合格 + AVAILABLE -> 断言 C400；
    //     不合格 + MAINTENANCE -> 断言成功。
    // 断言目标：非法组合被拒绝；合法组合完成后
    //     报修单 COMPLETED 且工具保持 MAINTENANCE。
    // 失败后果：缺陷工具被错误上架，造成安全隐患。
    // 该用例验证验收接口的参数组合约束。
    /**
     * 场景：维修完成后以不合格结果验收，并指定工具验收后状态。
     * 前置条件：部分或不合格验收不允许直接把工具置为可用。
     * 断言目标：不合格 + AVAILABLE 返回 400/C400；
     * 不合格 + MAINTENANCE 成功，报修单完成且工具保持维修中。
     */
    @Test
    void acceptance_combines_result_with_tool_target_status() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();

        // 验收前先把报修推进到“已核验 + 维修完成”，
        // 使用例只聚焦验收组合约束。
        // 部分合格 / 不合格不得直接置为可用
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "combo");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);
        // 统一把报修推进到可验收状态。
        verifyAndComplete(admin, reportId);

        // 不合格验收不能直接把工具置为可用。
        assertCode("C400", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "AVAILABLE")));
        // 不合格验收配合维修中状态则允许完成。
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "MAINTENANCE")));
        // 报修单进入完成状态。
        assertEquals("COMPLETED", report(reportId).getStatus());
        // 工具保持维修中，等待进一步处理。
        assertEquals("MAINTENANCE", toolMapper.selectById(toolId).getStatus());
    }

    // 覆盖点：验收合格但租赁仍未结束的工具状态。
    // 业务背景：工具虽然维修合格，但当前租赁仍未归还，
    //     不能在同一时刻把工具重新上架。
    // 前置条件：报修已完成、租赁仍处于取件状态。
    // 验证路径：验收合格并请求把工具置为 AVAILABLE。
    // 断言目标：报修单 COMPLETED；工具仍为 MAINTENANCE。
    // 失败后果：在租工具被重复出租，产生档期冲突。
    // 该用例验证“租赁结束”是恢复可用的必要条件。
    /**
     * 场景：维修合格但租赁单仍处于租出中时验收。
     * 前置条件：验收请求把目标状态指定为可用。
     * 断言目标：报修单 COMPLETED，但工具仍保持 MAINTENANCE，
     * 因为租赁尚未结束，不能把在租工具重新上架。
     */
    @Test
    void qualified_accept_keeps_tool_maintenance_while_rental_is_active() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "active");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);
        // 统一把报修推进到可验收状态。
        verifyAndComplete(admin, reportId);

        // 验收合格并请求把工具置为可用。
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        // 报修单正常完成。
        assertEquals("COMPLETED", report(reportId).getStatus());
        // 租赁仍在进行，工具必须保持维修中。
        assertEquals("MAINTENANCE", toolMapper.selectById(toolId).getStatus());
    }

    // 覆盖点：租赁结束后验收合格恢复工具可用。
    // 业务背景：当维修合格且租赁已经结束时，
    //     工具没有继续停在维修中的理由，应恢复可租。
    // 前置条件：报修已完成、租赁已 RETURNED。
    // 验证路径：把租赁改为 RETURNED -> 验收合格。
    // 断言目标：报修单 COMPLETED；工具 AVAILABLE。
    // 失败后果：合格工具长期停留在维修中，资产利用率下降。
    // 该用例与“租赁未结束”用例形成完整对照。
    /**
     * 场景：租赁单已归还后，对合格维修进行验收。
     * 前置条件：报修已完成、租赁已结束。
     * 断言目标：验收后报修单 COMPLETED 且工具恢复 AVAILABLE，
     * 证明工具恢复可用的两个前置条件都满足时才放行。
     */
    @Test
    void qualified_accept_makes_tool_available_after_rental_ended() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "ended");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);
        // 统一把报修推进到可验收状态。
        verifyAndComplete(admin, reportId);

        // 模拟租赁已归还。
        Rental rental = rentalMapper.selectById(rentalId);
        // 状态改为 RETURNED。
        rental.setStatus("RETURNED");
        // 更新租赁状态。
        rentalMapper.updateById(rental);

        // 验收合格并请求把工具置为可用。
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));

        // 报修单完成。
        assertEquals("COMPLETED", report(reportId).getStatus());
        // 租赁已结束，工具恢复可租。
        assertEquals("AVAILABLE", toolMapper.selectById(toolId).getStatus());
    }

    // 覆盖点：报废验收的终态性。
    // 业务背景：报废会同时关闭报修单并将工具移出资产池，
    //     之后不应允许重新维修或恢复。
    // 前置条件：报修已核验并推进到维修完成。
    // 验证路径：不合格验收并指定 SCRAPPED ->
    //     再尝试验收与更新进度。
    // 断言目标：报修单与工具均为 SCRAPPED；
    //     后续操作返回 C409。
    // 失败后果：已报废资产被重新打开，资产账实不符。
    // 该用例验证报修与工具两个终态的一致性。
    /**
     * 场景：以不合格结果验收并把工具报废，随后尝试重新验收或维修。
     * 前置条件：报废验收是报修与工具的共同终态。
     * 断言目标：报修单与工具均为 SCRAPPED；后续验收与进度更新返回 C409，
     * 防止已报废资产被重新打开或恢复。
     */
    @Test
    void scrapped_is_terminal_and_cannot_be_reopened() {
        // 管理员登录。
        String admin = adminLogin();
        // 种子用户登录。
        String user = userLogin();
        // 创建已审核工具。
        long toolId = createApprovedTool(admin, "scrapped");
        // 构造已取件的租赁单。
        long rentalId = createPickedUpRental(user, admin, toolId);
        // 用户提交报修。
        long reportId = createReport(user, rentalId);
        // 统一把报修推进到可验收状态。
        verifyAndComplete(admin, reportId);

        // 不合格验收，并把工具报废。
        ok(call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "UNQUALIFIED", "toolStatusAfter", "SCRAPPED")));

        // 报修单进入 SCRAPPED 终态。
        assertEquals("SCRAPPED", report(reportId).getStatus());
        // 工具同样进入 SCRAPPED，退出资产池。
        assertEquals("SCRAPPED", toolMapper.selectById(toolId).getStatus());
        // 报废后不能再次验收。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/accept", admin,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE")));
        // 报废后不能重新更新维修进度。
        assertCode("C409", call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "重新维修")));
    }

    // 辅助方法：把报修推进到“已核验 + 维修完成”。
    // 作为验收类用例的统一前置状态，减少重复步骤。
    // 两个请求都是成功路径，任一步失败都会立即暴露。
    /**
     * 辅助方法：把报修推进到“已核验 + 维修完成”，
     * 作为验收类用例的统一前置状态。
     */
    private void verifyAndComplete(String admin, long reportId) {
        // 核验通过。
        ok(call(HttpMethod.POST, base(reportId) + "/verify", admin,
                Map.of("verifyResult", "CONFIRMED")));
        // 维修完成。
        ok(call(HttpMethod.POST, base(reportId) + "/progress", admin,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成")));
    }

    // 辅助方法：拼接管理端报修单基础 URL。
    // 后续核验、进度、验收接口都以此为前缀。
    /** 辅助方法：拼接管理端报修单基础 URL。 */
    private String base(long reportId) {
        return "/api/admin/damage-reports/" + reportId;
    }

    // 辅助方法：以当前用户身份为指定租赁单创建报修并返回 ID。
    // 报修内容固定，便于断言与清理。
    /**
     * 辅助方法：以当前用户身份为指定租赁单创建报修并返回 ID。
     * 报修内容固定，便于断言与清理。
     */
    private long createReport(String user, long rentalId) {
        // 调用用户端报修接口，请求体只包含最小字段。
        return ok(call(HttpMethod.POST, "/api/user/damage-reports", user, Map.of(
                "rentalId", rentalId,
                "damagePart", "电源接口",
                "description", "通电后无反应",
                "images", List.of()))).get("data").get("id").asLong();
    }

    // 辅助方法：按 ID 读取报修单当前状态。
    // 直接查库可以验证接口响应之外的真实落库结果。
    /** 辅助方法：按 ID 读取报修单当前状态。 */
    private DamageReport report(long reportId) {
        return damageReportMapper.selectById(reportId);
    }

    // 辅助方法：创建工具预约、支付并取件，
    // 返回可直接报修的租赁单 ID。
    // 该链路走真实接口，保证报修前置状态与生产一致。
    /**
     * 辅助方法：创建工具预约、支付并取件，返回可直接报修的租赁单 ID。
     */
    // 用户提交预约。
    private long createPickedUpRental(String user, String admin, long toolId) {
        // 取出租赁单 ID。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", user, booking(toolId)));
        // 用户支付。
        long rentalId = created.get("data").get("id").asLong();
        // 管理员确认取件，租赁进入 PICKED_UP。
        ok(call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay", user, Map.of()));
        ok(call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup", admin, Map.of()));
        return rentalId;
    }

    // 预约时间取两天后，避开种子数据与其他用例占用的档期。
    // 去除纳秒并统一格式，保证接口解析稳定。
    /** 辅助方法：构造两天后开始的预约请求体。 */
    private Map<String, Object> booking(long toolId) {
        return Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国");
    }

    // 工具名称带测试前缀，审核通过后才能被预约与报修。
    // 其余字段使用最小合法值，减少无关数据。
    /** 辅助方法：创建唯一名称工具并审核通过。 */
    private long createApprovedTool(String admin, String suffix) {
        // 创建工具。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        long id = created.get("data").get("id").asLong();
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        return id;
    }

    // 辅助方法：双线程栅栏并发执行两个请求，
    // 尽量同时进入业务逻辑，放大并发竞争窗口。
    // 超时后强制关闭线程池，避免测试挂起。
    /**
     * 辅助方法：双线程栅栏并发执行两个请求，尽量同时进入业务逻辑。
     * 超时后强制关闭线程池，避免测试挂起。
     */
    private List<ApiResponse> concurrently(Supplier<ApiResponse> first,
                                           Supplier<ApiResponse> second) throws Exception {
        // 两个线程各自发出可执行信号。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // 主线程统一放行，制造同时进入业务的时机。
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            // 提交两个任务，任务内部先等待栅栏再执行请求。
            Future<ApiResponse> a = pool.submit(() -> await(ready, go, first));
            Future<ApiResponse> b = pool.submit(() -> await(ready, go, second));
            // 等待两个线程都就绪，超过 10 秒视为测试失败。
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            // 统一放行。
            go.countDown();
            // 收集两个结果，30 秒超时避免死锁导致测试永久挂起。
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        // 无论成功失败都关闭线程池，释放资源。
        } finally {
            pool.shutdownNow();
        }
    }

    // 辅助方法：线程就绪后等待统一放行，再执行实际请求。
    // 该结构确保两个请求尽可能同时到达服务端。
    // 并发辅助方法不参与业务断言，只负责制造同时进入的时机。
    /** 辅助方法：线程就绪后等待统一放行，再执行实际请求。 */
    private ApiResponse await(CountDownLatch ready, CountDownLatch go,
                              Supplier<ApiResponse> call) throws InterruptedException {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return call.get();
    }

    // 辅助方法：从响应体读取业务码。
    // 业务码是状态机与参数校验的主要断言依据。
    /** 辅助方法：从响应体读取业务码。 */
    private String code(ApiResponse response) {
        return response.body().get("code").asString();
    }

    // 辅助方法：断言业务码。
    // 期望 C409 时同时校验 HTTP 409，
    // 保证状态机冲突在协议层与业务层保持一致。
    // 期望成功时只校验业务码，由调用方决定是否继续断言 HTTP 状态。
    /**
     * 辅助方法：断言业务码，并在期望 C409 时同时校验 HTTP 409。
     */
    private void assertCode(String code, ApiResponse response) {
        assertEquals(code, code(response), response.body().toString());
        if ("C409".equals(code)) {
            assertEquals(409, response.status(), response.body().toString());
        }
    }

    // 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。
    // 成功路径统一走该方法，避免遗漏任一层的断言。
    // 成功响应返回完整 JSON 树，调用方可继续读取 data 字段。
    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", code(response), response.body().toString());
        return response.body();
    }

    // 辅助方法：种子管理员登录并返回令牌。
    // 管理员令牌用于核验、进度与验收等管理端操作。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 辅助方法：种子用户密码登录并返回令牌。
    // 用户令牌用于提交报修与查询用户侧数据。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    // 辅助方法：调用登录接口，断言成功并取出 tokenValue。
    // 登录失败会直接输出响应体，便于排查种子账号状态。
    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    private String login(String url, Map<String, Object> body) {
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。
    // 所有用例通过该方法发起请求，保证行为一致。
    // 请求方法、URL、令牌与请求体全部由调用方显式传入，
    // 避免辅助方法隐藏权限或数据来源。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 按 HTTP 方法构建请求。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 只有需要登录的接口才添加 Bearer 头。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 无请求体的请求不设置 Content-Type，避免服务端误判。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取 4xx/5xx 响应体，
        // 因此状态机冲突等失败场景也能被用例断言。
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
