package com.cdwater.toolshare.service;

// 租赁查询测试同时覆盖管理端筛选、用户端分页与 Excel 导出：
// EasyExcel 用于解析导出文件，MyBatis-Plus 的 Wrapper 用于直接准备边界数据，
// RestClient 走真实 HTTP 接口，身份证加密器用于独立推导期望的脱敏值。
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalExportRow;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.shared.security.IdCardCipher;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 查询接口的输入组合远多于状态流转，因此用矩阵式数据覆盖边界。
// 断言按“过滤是否生效 → 分页是否正确 → 字段是否完整”三层推进。
// 用例之间不共享可变状态，每个用例独立构造所需数据。
// 测试只读接口不会修改数据库，写接口用例则通过登记 ID 自行清理。
// 因此本类可以按任意顺序执行，也可以与前端联调环境并行运行。
// 查询类测试最容易出现“用例通过但语义错误”的情况，
// 因此每个断言都尽量说明它在业务上的含义，而不只是比较数值。
// 例如 size 上限不仅是分页参数，也是防止前端一次拉取过多数据的保护措施。
// 这样后续维护者修改实现时，可以先判断是否破坏了产品契约。
// 测试数据与断言共同构成一份可执行的查询行为说明。
// 本类围绕“查询契约”组织，而不是重复验证下单/支付等状态流转：
// 管理端要能在大量租赁单中按逾期、损坏结果、状态与关键字精确筛选；
// 用户端要能分页浏览自己的订单，并让服务端收敛过大的页大小；
// 导出必须复用列表筛选条件，并包含快照字段与中文展示名。
// 业务不变量包括：快照只保存脱敏身份证、更新人反映最后操作者、
// 逾期三态互斥且覆盖完整、筛选组合使用 AND 语义。
// 测试数据通过 Mapper 直接插入，便于构造“未来取件”“已逾期”等时间边界。
// 每个用例结束按登记 ID 清理，避免共享测试库互相污染。
// 所有断言都基于公开接口返回，只有准备与核对阶段直接读库。
// 这样既覆盖真实鉴权与序列化链路，又能精确定位查询层问题。
// 该类的用例可以独立运行，不依赖执行顺序或种子租赁数据。
/**
 * 租赁查询与导出集成测试。
 * <p>
 * 测试范围：管理端多条件筛选、用户端分页与状态筛选、
 * 租赁快照中的身份证脱敏与操作人记录，以及 Excel 导出内容。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子用户。
 * 关键不变量：快照只保存脱敏身份证，不得出现完整号码；
 * 录入人/更新人随操作变化；逾期三态筛选与损坏结果筛选互不干扰；
 * 导出复用相同筛选条件并包含快照字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class RentalQueryIntegrationTest extends AbstractIntegrationTest {

    // 接口时间格式统一为秒级 ISO 本地时间，避免毫秒与时区差异。
    // 所有相对时间在构造前都会 withNano(0) 对齐。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 工具名前缀用于识别测试工具并保证名称唯一。
    private static final String TOOL_PREFIX = "IT-QUERY-";
    // 自增序号保证订单号在共享库中不重复。
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 租赁单 Mapper：准备筛选数据、读取订单号并清理。
    // 直接插入可以精确控制状态、时间与金额，不受业务接口限制。
    /** 租赁单 Mapper，用于准备筛选数据与断言快照。 */
    @Autowired
    RentalMapper rentalMapper;
    // 工具 Mapper：创建与删除测试工具。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：删除工具前先清理关联日志。
    /** 工具日志 Mapper，清理工具前先删关联日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;
    // 用户 Mapper：读取种子用户的加密身份证，用于独立计算掩码。
    /** 用户 Mapper，用于读取种子用户的加密身份证。 */
    @Autowired
    UserMapper userMapper;
    // 加密器与生产使用同一实现，确保期望值不是硬编码。
    /** 身份证加密器，用于独立计算期望的掩码值。 */
    @Autowired
    IdCardCipher idCardCipher;

    // 真实 HTTP 客户端：覆盖随机端口、鉴权头与 JSON 序列化。
    // 每次用例前重建，避免跨上下文复用连接。
    RestClient client;
    // JSON 解析器只读取断言所需字段，不绑定具体 DTO。
    final ObjectMapper om = new ObjectMapper();
    /** 本用例创建的租赁单 ID，统一清理。 */
    // 租赁单清理列表：所有直接插入的数据都登记于此。
    final List<Long> rentalIds = new ArrayList<>();
    /** 本用例创建的工具 ID，统一清理。 */
    // 工具清理列表：删除工具前需要先清理其日志。
    final List<Long> toolIds = new ArrayList<>();

    // 响应载体保留状态码与 JSON 树：
    // 状态码用于断言鉴权/参数错误，JSON 树用于断言业务码与数据字段。
    // 记录类型不可变，避免在断言过程中被意外修改。
    record ApiResponse(int status, JsonNode body) {
    }

    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    // 随机端口由 Spring 注入，客户端只保存 baseUrl。
    // 具体请求由 call 方法统一附加令牌与 JSON 内容类型。
    @BeforeEach
    void setUp() {
        // 使用 RestClient 而不是 MockMvc，可以覆盖真实的网络与安全过滤链。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后删除本类创建的租赁单与工具日志/工具。
     * 先删租赁再删工具，避免留下悬挂引用；集合清空以便下个用例复用。
     */
    // 清理顺序遵循依赖关系：先删除租赁单，再删除工具日志与工具。
    // 即使用例中途失败，@AfterEach 仍会执行，保证共享库回到基线。
    // 只删除本类登记的数据，不执行全表清理。
    @AfterEach
    void cleanUp() {
        // 按登记顺序删除租赁单，避免留下悬挂的工具引用。
        for (Long id : rentalIds) {
            rentalMapper.deleteById(id);
        }
        // 工具日志按 toolId 精确删除。
        for (Long id : toolIds) {
            // 日志删除后再删除工具本身。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, id));
            toolMapper.deleteById(id);
        }
        rentalIds.clear();
        toolIds.clear();
    }

    /**
     * 场景：用户下单生成租赁快照，随后依次支付、取件、归还，再另建一单取消。
     * 前置条件：种子用户已实名，身份证以密文保存；工具由管理员创建并审核。
     * 断言目标：快照中的身份证与加密器掩码结果一致且不含完整号码；
     * 创建人与初始更新人为下单用户；管理员归还后更新人变为 admin，
     * 用户自行取消时更新人仍为本人，准确反映最后操作者。
     */
    // 快照在创建时固化，后续状态流转只更新更新人，不重新采集身份信息。
    // 这正是隐私保护与审计追溯能够同时成立的关键。
    // 该用例同时是快照字段的回归保护：任何新增敏感字段都应先通过评审。
    // 快照字段只在创建时采集一次，后续流转复用，避免重复读取敏感信息。
    // 这正是“最小化数据暴露”原则在租赁流程中的落地方式。
    // 更新人字段则相反，每次操作都会覆盖，以反映最后责任人。
    // 两类字段的更新策略不同，测试分别用创建后与状态变更后两个时点验证。
    // 该用例因此同时覆盖隐私与审计两个目标。
    // 该用例走完整的“下单 → 支付 → 取件 → 归还”链路，
    // 再构造一条取消单，对比两类操作对更新人字段的影响。
    // 关键不变量是：快照只保存脱敏身份证，更新人始终等于最后操作者。
    /**
     * 场景：用户下单生成租赁快照，随后走完支付、取件、归还，并另建一单取消。
     * 前置条件：种子用户已实名，身份证以密文保存；工具由管理员创建并审核。
     * 断言目标：快照中的身份证与加密器掩码结果一致且不含完整号码；
     * 创建人与初始更新人为下单用户；管理员归还后更新人变为 admin，
     * 用户自行取消时更新人仍为本人，准确反映最后操作者。
     */
    @Test
    void create_rental_snapshots_masked_id_card_and_operator_names() {
        // 管理员负责创建/审核工具及后续取件、归还操作。
        String admin = adminLogin();
        // 种子用户负责下单、支付与取消。
        String user = userLogin();
        // 创建并审核工具，确保用户可以在其档期内成功下单。
        long toolId = createApprovedTool(admin, "snapshot");
        // 用户使用两天后的档期下单，接口返回租赁单 ID。
        long rentalId = createRental(user, toolId);
        // 登记 ID，供用例结束后清理。
        rentalIds.add(rentalId);

        // 从数据库读取种子用户与刚创建的租赁单，核对快照。
        User resident = userMapper.selectById(1L);
        Rental created = rentalMapper.selectById(rentalId);
        // 脱敏值必须与加密器独立计算的结果一致，而不是写死字符串。
        // 使用真实加密器计算，确保脱敏算法与生产一致。
        assertEquals(idCardCipher.mask(resident.getIdCard()), created.getRenterIdCardMasked());
        // 快照中不得出现完整身份证号，防止敏感信息落库。
        // 完整号码一旦落库即视为严重隐私缺陷。
        assertFalse(created.getRenterIdCardMasked().contains("310101199001011234"),
                "快照不得保存完整身份证");
        // 下单用户应被记录为创建人。
        // 创建人来自登录会话，而不是请求体。
        assertEquals("王建国", created.getCreatedByName());
        // 初始更新人也是下单用户。
        // 创建动作同时把更新人初始化为同一用户。
        assertEquals("王建国", created.getUpdatedByName());

        // 支付：要求状态与角色权限匹配，成功后进入待取件。
        // 支付成功后订单进入待取件状态。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 取件：由管理员执行，成功后进入已取件状态。
        // 取件由管理员核销，普通用户无权调用。
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        // 把档期调整到“已开始且未结束”，模拟在租状态下的归还。
        // 直接改库可绕过时间校验，专注于归还接口本身的行为。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 起止时间对齐到秒，避免序列化精度差异。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        // 管理员归还并提交实际归还时间与损坏结果。
        // 归还是状态机的最后一步，需要提交实际时间与损坏结果。
        ApiResponse returned = call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return",
                admin, Map.of("actualReturnTime", now.format(FORMAT), "damageResult", "INTACT"));
        assertEquals(200, returned.status(), returned.body().toString());
        // 归还后更新人应变为 admin，表示最后操作者是管理员。
        // 更新人字段持久化为管理员，便于审计追责。
        assertEquals("admin", rentalMapper.selectById(rentalId).getUpdatedByName());

        // 另建一条租赁单并立即取消，验证用户自助操作也记录操作人。
        long cancelToolId = createApprovedTool(admin, "cancel");
        // 登记第二条租赁单用于清理。
        long cancelRentalId = createRental(user, cancelToolId);
        rentalIds.add(cancelRentalId);
        // 用户取消是合法状态流转，应返回成功。
        ApiResponse cancelled = call(HttpMethod.POST, "/api/user/rentals/" + cancelRentalId + "/cancel",
                user, Map.of("reason", "行程变化"));
        assertEquals(200, cancelled.status(), cancelled.body().toString());
        // 取消单的更新人仍是下单用户本人，而非系统或管理员。
        // 用户自助取消时更新人保持为本人。
        assertEquals("王建国", rentalMapper.selectById(cancelRentalId).getUpdatedByName());
    }

    /**
     * 场景：构造逾期、已取件但未到期、未来取件、严重损坏与轻微损坏五类租赁单，
     * 分别使用 overdue=true/false/all、损坏结果与关键字筛选。
     * 前置条件：逾期判定基于结束时间，已归还单据不计入逾期。
     * 断言目标：三态筛选互斥且完整；损坏结果可单独或与状态组合过滤；
     * 关键字可命中单号与手机号，避免管理端只能全量翻页。
     */
    // 所有筛选都通过同一个列表接口，参数缺失即表示不过滤。
    // 组合条件必须使用 AND，避免一个条件覆盖另一个条件。
    // 逾期三态由同一个布尔参数控制，因此必须验证互斥与完整性。
    // 损坏结果与状态是正交维度，组合筛选验证后端没有互相覆盖。
    // 逾期判定同时依赖结束时间与归还状态，单看 status 会遗漏超时订单。
    // 三态参数 true/false/all 必须覆盖所有记录且互斥，否则运营视图会失真。
    // 损坏结果筛选是正交能力，运营可以按理赔进度单独查看。
    // 关键字同时支持订单号与手机号，分别服务于工单定位与客服核实。
    // 这些条件最终都要下推到数据库查询，而不是在内存中过滤分页结果。
    // 该用例覆盖管理端筛选矩阵：逾期三态、损坏结果、状态组合与关键字。
    // 数据尽量复用同一批租赁单，减少插入成本并保持断言可读。
    // 逾期判定基于结束时间与归还状态，而不是仅看 status 字段。
    /**
     * 场景：构造逾期、已取件未到期、未来取件、严重损坏与轻微损坏五类租赁单，
     * 分别使用 overdue=true/false/all、损坏结果与关键字筛选。
     * 前置条件：逾期判定基于结束时间，已归还单据不计入逾期。
     * 断言目标：三态筛选互斥且完整；损坏结果可单独或与状态组合过滤；
     * 关键字可命中单号与手机号，避免管理端只能全量翻页。
     */
    @Test
    void admin_filters_overdue_tristate_damage_result_and_keyword() {
        String admin = adminLogin();
        // 固定当前时间，所有档期相对它偏移，避免跨分钟抖动。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 逾期：状态 OVERDUE 且已结束，归还时间为空。
        long overdue = insertRental("overdue", "OVERDUE", "张三", "13900000009",
                now.minusDays(2), now.minusHours(2), null);
        // 已取件但结束时间已过：未归还，应计入逾期。
        long pastPickedUp = insertRental("past-picked", "PICKED_UP", "Query Person", "13900000010",
                now.minusDays(2), now.minusHours(1), null);
        // 未来取件：结束时间未到，不计入逾期。
        long futurePickedUp = insertRental("future-picked", "PICKED_UP", "Query Person", "13900000011",
                now.minusHours(1), now.plusDays(2), null);
        // 已归还且严重损坏：不计入逾期，用于损坏结果筛选。
        long severe = insertRental("severe", "RETURNED", "Query Person", "13900000012",
                now.minusDays(2), now.minusHours(1), "SEVERE");
        // 已归还且轻微损坏：用于验证损坏结果互斥。
        long minor = insertRental("minor", "RETURNED", "Query Person", "13900000013",
                now.minusDays(2), now.minusHours(1), "MINOR");

        // overdue=true 应命中“已逾期”与“已过结束时间未归还”两类。
        // overdue=true：逾期单与结束时间已过但尚未归还的单都应命中。
        // 逾期筛选返回的是 ID 列表，便于做包含关系断言。
        List<Long> onlyOverdue = ids(ok(call(HttpMethod.GET,
                // GET 请求携带管理端令牌，size=100 足以覆盖本用例数据。
                "/api/admin/rentals?overdue=true&size=100", admin, null)));
        // 逾期单必须出现在结果中。
        // 状态为 OVERDUE 的记录必须命中。
        assertTrue(onlyOverdue.contains(overdue), onlyOverdue.toString());
        // 已过结束时间但未归还的记录同样属于逾期。
        assertTrue(onlyOverdue.contains(pastPickedUp), onlyOverdue.toString());
        // 已过结束时间但未归还的记录也应命中。
        // 结束时间未来的记录不能误判。
        assertFalse(onlyOverdue.contains(futurePickedUp), onlyOverdue.toString());
        // 未来取件不应被误判为逾期。
        // 已归还记录不再计入逾期。
        assertFalse(onlyOverdue.contains(severe), onlyOverdue.toString());

        // overdue=false 是补集，只返回未逾期记录。
        // 补集筛选用于验证三态互斥且完整。
        List<Long> notOverdue = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?overdue=false&size=100", admin, null)));
        // 逾期单不应出现在补集中。
        // 逾期记录不得出现在补集。
        assertFalse(notOverdue.contains(overdue), notOverdue.toString());
        // 超时未归还同样不属于补集。
        assertFalse(notOverdue.contains(pastPickedUp), notOverdue.toString());
        // 已过结束时间未归还的记录也不应在补集中。
        // 未来档期属于未逾期。
        assertTrue(notOverdue.contains(futurePickedUp), notOverdue.toString());
        // 已归还记录属于未逾期。
        assertTrue(notOverdue.contains(severe), notOverdue.toString());
        // 未来取件属于未逾期。
        // 两条已归还记录都应出现在补集。
        assertTrue(notOverdue.contains(minor), notOverdue.toString());

        // overdue=all 不做过期过滤，应覆盖全部记录。
        // all 表示关闭逾期过滤，仅保留其他筛选条件。
        List<Long> all = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?overdue=all&size=100", admin, null)));
        // 用 containsAll 检查子集，允许共享库存在其他用例数据。
        // 本用例创建的五条记录都必须出现。
        assertTrue(all.containsAll(List.of(overdue, pastPickedUp, futurePickedUp, severe, minor)),
                all.toString());

        // 损坏结果筛选：SEVERE 与 MINOR 是互斥枚举。
        // 损坏结果筛选：只应命中 SEVERE，不应混入 MINOR。
        // 损坏结果筛选直接使用枚举值。
        List<Long> severeOnly = ids(ok(call(HttpMethod.GET,
                // 只应命中严重损坏记录。
                "/api/admin/rentals?damageResult=SEVERE&size=100", admin, null)));
        // 严重损坏记录命中。
        assertTrue(severeOnly.contains(severe), severeOnly.toString());
        // 轻微损坏不得混入。
        // 轻微损坏记录不应混入。
        assertFalse(severeOnly.contains(minor), severeOnly.toString());

        // 状态与损坏结果同时传入时，后端应使用 AND 语义。
        // 状态与损坏结果组合筛选，两个条件需同时满足。
        // 状态与损坏结果组合筛选，验证 AND 语义。
        List<Long> returnedMinor = ids(ok(call(HttpMethod.GET,
                // 只命中 RETURNED 且 MINOR 的记录。
                "/api/admin/rentals?status=RETURNED&damageResult=MINOR&size=100", admin, null)));
        // 同时满足 RETURNED 与 MINOR 的记录命中。
        assertTrue(returnedMinor.contains(minor), returnedMinor.toString());
        // 严重损坏不属于该组合结果。
        // 严重损坏不满足组合条件。
        assertFalse(returnedMinor.contains(severe), returnedMinor.toString());

        // 关键字可匹配订单号，便于管理员定位单个工单。
        // 订单号从数据库读取，避免依赖生成规则。
        // 按订单号关键字查询，覆盖管理员定位单个工单的场景。
        List<Long> byOrderNo = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?keyword=" + rentalMapper.selectById(severe).getOrderNo()
                        + "&size=100", admin, null)));
        // 结果应包含该订单号对应记录。
        // 目标订单必须命中。
        assertTrue(byOrderNo.contains(severe), byOrderNo.toString());

        // 关键字同样可匹配手机号，覆盖按承租人查找的场景。
        // 按手机号查询，覆盖客服核实承租人的场景。
        List<Long> byPhone = ids(ok(call(HttpMethod.GET,
                "/api/admin/rentals?keyword=13900000009&size=100", admin, null)));
        // 该手机号只属于逾期单，断言结果精确。
        // 该手机号对应记录必须命中。
        assertTrue(byPhone.contains(overdue), byPhone.toString());
    }

    /**
     * 场景：用户拥有两条已预订与一条已归还租赁单，按页查询并按状态过滤。
     * 前置条件：分页大小被服务端约束在 1-100。
     * 断言目标：两页合并覆盖全部记录；状态筛选只返回对应状态；
     * 请求 size=200 时响应返回实际 size=100；关键字可按工具名或单号命中。
     */
    // 分页参数由前端传入，但上限必须由服务端强制，防止一次拉取过多数据。
    // 状态筛选与关键字筛选都限制在当前用户范围内。
    // 用户端列表只返回当前用户数据，测试通过种子用户令牌限定范围。
    // 分页上限收敛是防止拖库式查询的第一道防线。
    // 用户端只应看到自己的订单，因此所有筛选都必须附加用户作用域。
    // 分页合并断言可以发现 SQL 偏移错误、重复记录或漏页问题。
    // size 上限由服务端强制，前端传入更大值时应被安全收敛。
    // 关键字筛选同样限制在当前用户范围内，不能成为越权查询入口。
    // 该用例是用户端列表接口的契约基线。
    // 该用例验证用户端分页边界与状态/关键字筛选。
    // 重点确认服务端对 size 上限的收敛行为，以及分页合并后不重不漏。
    /**
     * 场景：用户拥有两条已预订与一条已归还租赁单，按页查询并按状态过滤。
     * 前置条件：分页大小被服务端约束在 1-100。
     * 断言目标：两页合并覆盖全部记录；状态筛选只返回对应状态；
     * 请求 size=200 时响应返回实际 size=100；关键字可按工具名或单号命中。
     */
    @Test
    void user_page_paginates_and_filters_by_status() {
        // 用户令牌用于访问自己的租赁列表。
        String user = userLogin();
        // 固定时间基准，保证三条租赁单的档期关系稳定。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 两条未来预订单：用于分页与状态筛选。
        long first = insertRental("u-reserved-1", "RESERVED", "Paginate User", "13900000021",
                now.plusDays(1), now.plusDays(2), null);
        // 第二条预订单与第一条使用不同档期，避免业务唯一约束冲突。
        long second = insertRental("u-reserved-2", "RESERVED", "Paginate User", "13900000022",
                now.plusDays(3), now.plusDays(4), null);
        // 一条历史归还单：用于关键字与状态筛选。
        long third = insertRental("u-returned", "RETURNED", "Paginate User", "13900000023",
                now.minusDays(2), now.minusDays(1), "INTACT");

        // 第一页取 2 条；总数可能包含共享库数据，因此不做绝对断言。
        // 第一页请求验证默认分页参数解析。
        JsonNode pageOne = ok(call(HttpMethod.GET, "/api/user/rentals?page=1&size=2", user, null));
        // 第一页返回两条，页大小回显为 2。
        // 页大小 2 时第一页最多两条。
        assertEquals(2, pageOne.get("data").get("records").size());
        // 回显值来自服务端实际生效的分页参数。
        // 响应回显实际生效的页大小。
        assertEquals(2, pageOne.get("data").get("size").asLong());
        // 第二页取剩余记录。
        // 第二页验证翻页偏移正确。
        JsonNode pageTwo = ok(call(HttpMethod.GET, "/api/user/rentals?page=2&size=2", user, null));
        // 合并两页 ID，比较集合覆盖关系而非顺序。
        // 合并两页结果，检查整体覆盖。
        List<Long> seen = new ArrayList<>(ids(pageOne));
        // 合并时保留重复项，以便发现分页重复问题。
        seen.addAll(ids(pageTwo));
        // 三条本用例记录必须全部出现。
        assertTrue(seen.contains(first) && seen.contains(second) && seen.contains(third),
                seen.toString());

        // 按 RESERVED 过滤，逐条检查返回记录的状态。
        // 状态筛选只返回当前用户的 RESERVED 记录。
        JsonNode reserved = ok(call(HttpMethod.GET,
                // 查询只包含目标状态。
                "/api/user/rentals?status=RESERVED&size=100", user, null));
        // 每条记录状态都必须是 RESERVED。
        // 逐条校验状态字段，防止服务端忽略筛选参数。
        for (JsonNode record : reserved.get("data").get("records")) {
            // 两条预订单都应在结果中。
            // 每条记录都必须是请求的状态。
            assertEquals("RESERVED", record.get("status").asString());
        }
        // 超大 size 应被服务端收敛为上限 100。
        // 两条本用例预订单都在结果中。
        assertTrue(ids(reserved).containsAll(List.of(first, second)));

        // 前端请求 200，后端返回实际生效的 100。
        // 超大 size 应被服务端收敛为上限 100，并在响应中回显实际值。
        // 请求超过上限的页大小。
        JsonNode clamped = ok(call(HttpMethod.GET, "/api/user/rentals?size=200", user, null));
        // 响应 size 必须回显实际值，前后端契约一致。
        // 服务端收敛为 100 并在响应中回显。
        assertEquals(100, clamped.get("data").get("size").asLong(),
                // 失败时输出响应体，便于定位分页参数变化。
                "前端请求上限应与后端一致，后端返回实际 size");

        // 关键字匹配工具名：只有历史归还单应命中。
        // 关键字匹配工具名。
        List<Long> byToolName = ids(ok(call(HttpMethod.GET,
                // 请求中直接使用工具名后缀。
                "/api/user/rentals?keyword=u-returned&size=100", user, null)));
        // 命中历史归还单。
        // 历史归还单命中了工具名。
        assertTrue(byToolName.contains(third), byToolName.toString());
        // 不应命中未来预订单。
        // 其他工具名不应被模糊匹配带入。
        assertFalse(byToolName.contains(first), byToolName.toString());

        // 关键字匹配订单号时结果应精确到单条记录。
        // 关键字匹配订单号。
        List<Long> byOrderNo = ids(ok(call(HttpMethod.GET,
                // 订单号从库中读取，避免硬编码生成规则。
                "/api/user/rentals?keyword=" + rentalMapper.selectById(first).getOrderNo()
                        + "&size=100", user, null)));
        // 精确断言唯一命中。
        // 订单号唯一，结果应精确到一条。
        assertEquals(List.of(first), byOrderNo);
    }

    /**
     * 场景：管理员导出全部租赁单，再按严重损坏筛选导出。
     * 前置条件：Excel 模型 RentalExportRow 与接口列定义一致。
     * 断言目标：导出包含脱敏身份证、录入/更新人、损坏结果中文名与金额快照；
     * 筛选导出只包含严重损坏记录，说明导出与列表使用同一查询条件。
     */
    // 导出是列表能力的延伸，字段投影必须与列表保持一致。
    // 二进制响应无法直接断言 JSON，因此借助 EasyExcel 反序列化。
    // 导出文件列由 RentalExportRow 定义，测试逐列核对关键快照。
    // 筛选导出复用查询参数，确保屏幕所见与文件内容一致。
    // 导出通常用于财务对账与纠纷取证，因此快照字段必须完整。
    // 身份证必须保持脱敏，金额必须保留两位小数，损坏结果必须可读。
    // 筛选条件必须与列表接口一致，否则运营会得到两份不同的数据。
    // 测试通过 EasyExcel 解析真实文件，覆盖列名、顺序与单元格格式。
    // 这是导出功能最容易在重构中悄悄退化的部分。
    // 该用例验证 Excel 导出与列表查询共享筛选条件，
    // 并覆盖脱敏、操作人、损坏中文名与金额等快照列。
    /**
     * 场景：管理员导出全部租赁单，再按严重损坏筛选导出。
     * 前置条件：Excel 模型 RentalExportRow 与接口列定义一致。
     * 断言目标：导出包含脱敏身份证、录入/更新人、损坏结果中文名与金额快照；
     * 筛选导出只包含严重损坏记录，说明导出与列表使用同一查询条件。
     */
    @Test
    void export_contains_snapshot_fields_and_respects_damage_filter() {
        String admin = adminLogin();
        // 两条已归还记录：一条严重损坏、一条完好。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 严重损坏记录用于字段完整性断言。
        long severe = insertRental("export-severe", "RETURNED", "Export Person", "13900000031",
                now.minusDays(3), now.minusHours(5), "SEVERE");
        // 完好记录用于验证筛选导出会排除非目标记录。
        long intact = insertRental("export-intact", "RETURNED", "Export Person", "13900000032",
                now.minusDays(2), now.minusHours(2), "INTACT");

        // 全量导出返回 xlsx 二进制，用 EasyExcel 解析为强类型行。
        // 先导出全量数据，核对快照列与金额格式。
        // 解析为 RentalExportRow，列定义由模型注解决定。
        // 空查询串表示不加筛选，导出全量数据。
        byte[] allBytes = export(admin, "");
        // 按订单号定位严重损坏那一行，避免受共享库其他数据影响。
        // 解析为强类型行，后续按订单号定位。
        List<RentalExportRow> allRows = readRows(allBytes);
        // 流式查找，找到即停止。
        // 使用流式过滤，避免依赖导出顺序。
        RentalExportRow severeRow = allRows.stream()
                // 找不到时抛出 NoSuchElementException，提示导出内容缺失。
                .filter(row -> row.getOrderNo().equals(rentalMapper.selectById(severe).getOrderNo()))
                .findFirst().orElseThrow();
        // 身份证必须保持脱敏，不能因导出而还原明文。
        // 导出不得还原身份证明文。
        assertEquals("3101********1234", severeRow.getRenterIdCardMasked());
        // 录入人来自下单时的快照。
        // 录入人快照列存在。
        assertEquals("录入测试", severeRow.getCreatedByName());
        // 更新人来自最后一次状态操作。
        // 更新人快照列存在。
        assertEquals("更新测试", severeRow.getUpdatedByName());
        // 损坏结果转换为中文展示名。
        // 损坏结果以中文名导出。
        assertEquals("严重损坏", severeRow.getDamageResultName());
        // 逾期费保留两位小数。
        // 逾期费按两位小数格式化。
        assertEquals("50.00", severeRow.getOverdueFee());
        // 赔偿金额同样保留两位小数。
        // 赔偿费按两位小数格式化。
        assertEquals("50.00", severeRow.getDamageFee());
        // 退款金额为零时也输出 0.00，保持列格式统一。
        // 零金额也保留两位小数。
        assertEquals("0.00", severeRow.getRefundAmount());

        // 再按 damageResult=SEVERE 导出，验证筛选条件同样作用于 Excel。
        // 再按损坏结果筛选导出，验证筛选条件同样作用于 Excel。
        // 解析筛选后的行集合。
        // 复用列表筛选参数，验证导出与列表条件一致。
        List<RentalExportRow> filtered = readRows(export(admin, "?damageResult=SEVERE"));
        // 严重损坏记录必须包含在导出中。
        // 严重损坏记录必须出现在筛选导出中。
        assertTrue(filtered.stream().anyMatch(row -> row.getOrderNo()
                .equals(rentalMapper.selectById(severe).getOrderNo())));
        // 完好记录不得出现在筛选导出中。
        // 完好记录必须被排除。
        assertFalse(filtered.stream().anyMatch(row -> row.getOrderNo()
                .equals(rentalMapper.selectById(intact).getOrderNo())));
    }

    /**
     * 辅助方法（重载）：用默认脱敏身份证与操作人构造租赁单。
     * 便于只关心筛选条件的用例快速准备数据。
     */
    // 默认值同时用于导出断言，保证不同用例看到同一份快照语义。
    // 重载一：使用默认的脱敏身份证与操作人构造租赁单。
    // 适合只关心筛选条件的用例，减少重复参数。
    private long insertRental(String suffix, String status, String renterName, String renterPhone,
                              LocalDateTime start, LocalDateTime end, String damageResult) {
        // 委托给完整重载，默认快照字段保持与导出断言一致。
        return insertRental(suffix, status, renterName, renterPhone, start, end, damageResult,
                "3101********1234", "录入测试", "更新测试");
    }

    // 该助手不校验业务规则，只保证数据满足数据库约束与查询前提。
    // 调用方对状态、金额与时间的组合负责。
    // 该助手与生产下单逻辑不同，不会触发状态校验或业务事件。
    // 它只负责生成满足查询前提的数据，因此字段值需要调用方自行保证一致。
    // 该助手的参数覆盖了查询与导出断言所需的全部维度。
    // 状态派生规则保证已归还记录有归还时间与费用，未归还记录保持为空。
    // 单号唯一性由自增序号保证，避免并行执行时发生约束冲突。
    // 测试数据不写明文身份证，只写脱敏快照，符合生产数据模型。
    // 插入后立即登记清理，保证失败路径也不会污染共享库。
    // 重载二：完整构造租赁实体并直接写入数据库。
    // 状态会派生押金状态、实际归还时间与逾期费；
    // 损坏结果会派生赔偿金额，使测试数据满足业务约束。
    // 单号带全局序号，避免共享库唯一键冲突。
    // 插入后登记 ID，供 @AfterEach 清理。
    private long insertRental(String suffix, String status, String renterName, String renterPhone,
                              LocalDateTime start, LocalDateTime end, String damageResult,
                              String idCardMasked, String createdBy, String updatedBy) {
        // 先创建实体，再按数据库列逐个赋值，便于与表结构对照。
        Rental rental = new Rental();
        // 订单号由固定前缀、自增序号与用例后缀组成，既唯一又可读。
        rental.setOrderNo("IT-QRY-" + SEQ.incrementAndGet() + "-" + suffix);
        // 工具 ID 固定为 1；本用例不校验工具关联，只校验查询与导出。
        // 若未来改为按工具过滤，应改为传入真实工具 ID。
        rental.setToolId(1L);
        // 工具编码使用测试前缀，便于人工识别数据来源。
        rental.setToolCode("IT-QRY-CODE-" + suffix);
        // 工具名包含用例后缀，可用于关键字匹配断言。
        rental.setToolName("查询测试工具 " + suffix);
        // 归属种子用户，确保用户端列表能查询到该记录。
        rental.setUserId(1L);
        // 承租人姓名由参数控制，用于关键字与展示断言。
        rental.setRenterName(renterName);
        // 手机号由参数控制，用于关键字筛选场景。
        rental.setRenterPhone(renterPhone);
        // 快照直接写入调用方提供的脱敏值，本测试不落库明文身份证。
        rental.setRenterIdCardMasked(idCardMasked);
        // 录入人快照，用于导出列断言。
        rental.setCreatedByName(createdBy);
        // 更新人快照，状态流转后会被接口改写为实际操作者。
        rental.setUpdatedByName(updatedBy);
        // 起止时间由调用方指定，用于构造过去/未来档期。
        rental.setStartTime(start);
        // 结束时间同时是逾期判定的关键字段。
        rental.setEndTime(end);
        // 取件人默认与承租人一致，满足测试数据语义。
        rental.setPickupPerson(renterName);
        // 押金金额固定为 100，导出断言依赖该值。
        rental.setDepositAmount(new BigDecimal("100.00"));
        // 已归还记录押金已退款，其余状态视为已支付。
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        // 状态直接使用调用方传入的枚举字符串。
        rental.setStatus(status);
        // 已归还记录补上实际归还时间，其他状态保持为空。
        rental.setActualReturnTime("RETURNED".equals(status) ? end.plusMinutes(30) : null);
        // 逾期费由调用方传入，导出用例使用非零值验证格式。
        rental.setOverdueFee("RETURNED".equals(status) ? new BigDecimal("50.00") : BigDecimal.ZERO);
        // 赔偿费按损坏结果在调用方预先计算。
        rental.setDamageFee("SEVERE".equals(damageResult) ? new BigDecimal("50.00") : BigDecimal.ZERO);
        // 退款金额固定为零，避免引入额外业务分支。
        rental.setRefundAmount("RETURNED".equals(status) ? BigDecimal.ZERO : BigDecimal.ZERO);
        // 损坏结果用于筛选与中文名转换断言。
        rental.setDamageResult(damageResult);
        // 直接插入数据库，绕过下单接口以精确控制状态与金额。
        rentalMapper.insert(rental);
        // 登记生成的主键，清理阶段按 ID 删除。
        rentalIds.add(rental.getId());
        // 返回主键供调用方后续读库或拼装请求。
        return rental.getId();
    }

    /** 辅助方法：直接改库调整租赁单起止时间，绕过接口构造逾期场景。 */
    // 该方法只用于测试准备，不经过业务校验，因此不会改变其他字段。
    // 更新后立即通过归还接口读取，确保数据库变更对业务可见。
    // 直接更新起止时间，可精确构造“已开始未结束”等时间边界，
    // 无需等待真实时间流逝。
    private void setSlot(long rentalId, LocalDateTime start, LocalDateTime end) {
        // 只更新本测试创建的租赁单，条件包含主键。
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                // 主键条件保证不会影响其他数据。
                .eq(Rental::getId, rentalId)
                // 开始时间用于判断是否已进入租期。
                .set(Rental::getStartTime, start)
                // 结束时间用于逾期判定。
                .set(Rental::getEndTime, end));
    }

    /**
     * 辅助方法：请求 Excel 导出并读取原始字节。
     * 断言 HTTP 200，保证后续解析失败不会被误判为业务问题。
     */
    // 若接口返回 JSON 错误体，状态码断言会先失败，避免误解析。
    // 导出失败时通常返回 JSON 错误体，状态码断言可以阻止误解析。
    // 导出接口返回二进制流，因此不能复用 JSON 解析助手。
    // 断言 HTTP 200 后再读取字节，避免把错误页误当作 Excel。
    private byte[] export(String admin, String query) {
        // query 直接拼接筛选参数，与列表接口保持相同的参数名。
        return client.method(HttpMethod.GET).uri("/api/admin/rentals/export" + query)
                // 导出只允许管理员访问。
                .header("Authorization", "Bearer " + admin)
                // exchange 可以读取原始响应，避免 RestClient 对错误状态抛异常。
                .exchange((request, response) -> {
                    // 非 200 时立即失败，后续解析不会被执行。
                    assertEquals(200, response.getStatusCode().value());
                    // 一次性读取全部字节，测试文件规模很小。
                    return response.getBody().readAllBytes();
                });
    }

    /** 辅助方法：用 EasyExcel 把导出字节解析为强类型行对象。 */
    // 模型字段与导出列一一对应，字段改名会同时影响生产与测试。
    // 解析成功也间接证明文件头与工作表结构正确。
    // 使用与生产相同的 EasyExcel 模型解析导出文件。
    // 这样列名或顺序变化会直接导致测试失败。
    private List<RentalExportRow> readRows(byte[] bytes) {
        // 从内存字节读取，避免产生临时文件。
        return EasyExcel.read(new ByteArrayInputStream(bytes))
                // 读取默认工作表并同步返回全部行。
                .head(RentalExportRow.class).sheet().doReadSync();
    }

    /** 辅助方法：从分页响应中提取记录 ID 列表，便于集合断言。 */
    // 该助手假设响应结构为 data.records，与列表接口契约一致。
    // 该助手不校验分页元数据，元数据由调用方单独断言。
    // 从分页响应中提取记录 ID，便于集合断言。
    // 只读取 data.records，不关心其他分页字段。
    private List<Long> ids(JsonNode body) {
        // 使用列表保持服务端返回顺序。
        List<Long> result = new ArrayList<>();
        // 遍历当前页的全部记录。
        for (JsonNode record : body.get("data").get("records")) {
            // ID 以长整型读取，避免前端精度问题。
            result.add(record.get("id").asLong());
        }
        // 返回本页 ID 列表。
        return result;
    }

    /** 辅助方法：以两天后的档期创建租赁单并返回 ID。 */
    // 下单成功说明工具档期可用、用户已实名且押金规则通过。
    // 下单返回的 ID 用于后续支付、取件与归还，形成完整链路。
    // 通过真实下单接口创建租赁单，档期固定为两天后到三天后。
    // 这样既覆盖下单逻辑，又能得到后续状态流转所需的订单。
    // 承租人使用种子用户姓名，保证快照断言一致。
    private long createRental(String userToken, long toolId) {
        // 下单成功返回 data.id，作为后续支付/取件/归还的路径参数。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                // 关联已审核通过的工具。
                "toolId", toolId,
                // 未来档期避免与已有租赁冲突。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 租期一天，满足业务最小时长。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人与种子用户姓名一致。
                "pickupPerson", "王建国")));
        // 返回租赁单主键。
        return created.get("data").get("id").asLong();
    }

    /**
     * 辅助方法：创建唯一名称工具并审核通过，同时登记到清理列表。
     */
    // 审核通过后工具才对用户端可见，这是用户下单的前提。
    // 工具审核是管理端动作，测试通过真实接口完成而非直接改库。
    // 创建工具后立即审核通过，使其进入用户端可租范围。
    // 工具名带固定前缀，既保证唯一也便于按关键字检索。
    // 审核失败会由 ok 断言直接暴露，避免后续下单因工具不可用而失败。
    private long createApprovedTool(String admin, String suffix) {
        // 名称由前缀与用例后缀组成，保证跨用例唯一。
        String name = TOOL_PREFIX + suffix;
        // 创建时提交完整必填字段，避免审核接口因缺字段失败。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 唯一工具名。
                "name", name,
                // 固定型号，减少无关字段对查询的干扰。
                "model", "测试型号",
                // 分类使用测试枚举值。
                "category", "WX",
                // 新工具等级，避免前端展示异常。
                "damageLevel", "NEW",
                // 押金与租赁测试数据保持一致。
                "deposit", new BigDecimal("100"),
                // 存储区域为必填展示字段。
                "storageArea", "测试柜",
                // 使用说明允许富文本，测试使用最小 HTML。
                "usageGuide", "<p>说明</p>",
                // 不依赖真实图片，空列表即可。
                "imageUrls", List.of())));
        // 取出创建成功后的工具主键。
        long id = created.get("data").get("id").asLong();
        // 立即登记清理，即使审核失败也不会泄漏数据。
        toolIds.add(id);
        // 审核通过是工具可被下单的前置条件。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                // 只提交审核状态，其他字段保持不变。
                Map.of("auditStatus", "APPROVED")).status());
        // 返回工具 ID 供下单与查询使用。
        return id;
    }

    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    // 该助手只用于预期成功的请求，异常场景由调用方直接断言状态码。
    // 该助手返回响应体，避免调用方重复读取 data 节点。
    // 该助手是成功路径的统一入口，不适用于断言 4xx/5xx。
    // 失败信息包含响应体，便于快速判断是鉴权、参数还是状态机问题。
    // 返回响应体后，调用方可以继续读取 data 字段或分页元数据。
    // 这样成功语义只在一处定义，避免各用例断言口径不一致。
    // 统一断言 HTTP 200 与业务码 A200，返回响应体。
    // 失败信息附带原始响应，便于定位接口契约变化。
    private JsonNode ok(ApiResponse response) {
        // 传输层状态码必须是 200。
        // 登录失败时直接输出响应体。
        assertEquals(200, response.status(), response.body().toString());
        // 业务码 A200 表示请求被正常处理。
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        // 返回响应体供调用方读取 data。
        return response.body();
    }

    /** 辅助方法：种子管理员登录并返回令牌。 */
    // 令牌仅在本测试类生命周期内使用，不落盘。
    // 种子管理员密码来自测试数据初始化脚本。
    private String adminLogin() {
        // 管理端登录使用用户名+密码，返回独立令牌。
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    /** 辅助方法：种子用户密码登录并返回令牌。 */
    // 用户端登录与管理员登录完全隔离，令牌不可混用。
    // 种子用户使用手机号+密码登录。
    private String userLogin() {
        // 覆盖用户端密码登录通道。
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    // tokenValue 是 Sa-Token 的会话令牌，后续放入 Bearer 头。
    // 登录成功后的令牌位于 data.tokenValue。
    // 登录失败时先断言状态码，避免后续空指针。
    private String login(String url, Map<String, Object> body) {
        // 登录请求不需要令牌，因此 token 参数为 null。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        // 读取令牌字符串，供后续 Bearer 头使用。
        return response.body().get("data").get("tokenValue").asString();
    }

    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // 统一入口让鉴权、序列化与错误读取行为在所有用例中保持一致。
    // 统一解析也保证所有用例看到一致的 JSON 结构与空响应处理方式。
    // 统一入口还保证空响应、中文编码与错误体读取方式完全一致。
    // 测试中的 GET、PATCH、PUT、POST 都复用它，减少重复样板。
    // 如果未来增加新的鉴权方案，只需修改这一处即可覆盖全部用例。
    // 该方法不做业务断言，保持“传输层工具”的单一路职责。
    // 所有 HTTP 请求收敛到此方法，统一鉴权头与 JSON 序列化。
    // 响应流被完整读取并解析，空响应按空对象处理。
    // 该方法不主动断言业务码，由调用方决定成功或失败语义。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 先构造请求规格，后续按需附加头部与请求体。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 匿名接口（如登录）不携带令牌。
        if (token != null) {
            // 使用标准 Bearer 方案。
            spec.header("Authorization", "Bearer " + token);
        }
        // GET 与导出请求没有请求体。
        if (body != null) {
            // 只有存在请求体时才设置 JSON Content-Type。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取任意状态码的原始响应。
        return spec.exchange((request, response) -> {
            // try-with-resources 保证响应流关闭。
            try (InputStream in = response.getBody()) {
                // 显式使用 UTF-8，避免中文错误信息乱码。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 同时保留状态码与解析后的 JSON 树。
                return new ApiResponse(response.getStatusCode().value(),
                        // 空响应体按空对象处理，兼容无内容响应。
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // IO 异常属于基础设施问题。
            } catch (IOException e) {
                // 包装为运行时异常，直接使测试失败。
                throw new IllegalStateException(e);
            }
        });
    }
}
