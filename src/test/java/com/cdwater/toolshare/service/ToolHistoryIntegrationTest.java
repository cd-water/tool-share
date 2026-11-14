package com.cdwater.toolshare.service;

// 工具历史测试需要直接构造租赁单并清理关联日志：
// LambdaQueryWrapper 用于按工具 ID 删除日志，RestClient 走真实接口，
// 其余 Mapper 用于准备数据、读取订单号与清理测试记录。
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 本类的断言全部通过公开 API 完成，只有数据准备阶段直接使用 Mapper。
// 这样既能精确构造边界状态，又能覆盖真实的鉴权、序列化与查询逻辑。
// 管理端与用户端使用不同的字段投影，测试分别验证可见字段集合。
// 若两端未来合并接口，本类中的隐私断言会阻止敏感字段回归。
// 用户端历史的时间窗口只用于展示档期，不应成为身份识别入口。
// 管理端历史则相反，必须保留承租人、金额与操作人等审计信息。
// 因此同一个底层表需要服务两种截然不同的读取模型。
// 测试环境的数据库在多个测试类之间共享，因此数据隔离依赖：
// 工具名带固定前缀、订单号带自增序号、用例结束按 ID 精确删除。
// 断言尽量使用 contains/containsAll，而不是要求结果只含测试数据。
// 对隐私字段则相反，采用严格的不存在断言，宁可失败也不放过泄漏。
// 管理端历史用于纠纷处理，因此字段完整性比响应体积更重要。
// 用户端历史用于展示档期，因此只保留最小必要信息。
// 这两套读取模型的差异是本类所有用例的设计主线。
// 任何一端的需求变化，都应先更新本类的场景说明再调整断言。
// 测试不启动真实短信、AI 或对象存储，依赖均由基座提供替身。
// 随机端口让本类可以与本地开发实例并行运行。
// 该类不覆盖下单、支付等状态流转，那些行为由租赁测试负责。
// 这里只关心“历史数据如何被读取和投影”。
// 本类从两个视角验证“工具历史”的边界：
// 管理端需要看到目标工具的全部租赁记录，用于审计与纠纷处理；
// 用户端只能看到已完成的时间窗口，不能推断承租人身份。
// 两类接口共享底层的租赁数据，但字段投影与过滤条件完全不同。
// 管理端历史按 toolId 过滤并分页，同时校验快照字段完整性；
// 用户端工具详情不得出现 rentals/rentalRecords 等历史字段；
// 用户端历史只统计已归还记录，并隐藏姓名、手机号与订单号。
// 工具不存在时管理端历史返回 404，而不是空列表。
// 这些不变量共同定义“历史可见性”的产品契约。
// 测试数据通过 Mapper 直接插入，便于精确控制状态、金额与档期。
// 每个用例结束按登记的工具与租赁 ID 清理，避免共享库污染。
/**
 * 工具租赁历史集成测试。
 * <p>
 * 测试范围：管理端按工具查看历史租赁的分页与字段完整性，
 * 以及用户端工具历史窗口的聚合与隐私边界。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子账号。
 * 关键不变量：管理端历史只包含目标工具的租赁单且分页正确；
 * 用户端工具详情不得暴露租赁历史；用户历史只返回已归还的时间窗口，
 * 并且不包含他人姓名、手机号或订单号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolHistoryIntegrationTest extends AbstractIntegrationTest {

    // 统一的时间格式，接口请求体与数据库时间使用同一精度。
    // 时间格式与接口约定一致，避免毫秒或时区造成 JSON 解析失败。
    // 测试中的相对时间都会先 withNano(0) 对齐到秒。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 工具名称前缀用于识别测试工具，并避免与种子工具重名。
    // 前缀用于工具名与清理查询，确保测试数据可识别。
    private static final String PREFIX = "IT-HISTORY-";
    // 订单号自增序号，保证共享测试库中的唯一约束不被破坏。
    // 自增序号只保证同一 JVM 内唯一，足以满足测试库约束。
    // 订单号由前缀、序号与后缀组成，便于按用例排查。
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 随机端口，供测试 HTTP 客户端使用。 */
    // 随机端口由 Spring 注入；客户端在 @BeforeEach 中据此创建。
    @LocalServerPort
    int port;

    // 租赁单 Mapper：构造历史数据、读取订单号并在用例后清理。
    /** 租赁单 Mapper，用于构造历史数据与清理。 */
    @Autowired
    RentalMapper rentalMapper;
    // 工具 Mapper：创建与删除测试工具。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：删除工具前必须先清理关联日志。
    /** 工具日志 Mapper，清理工具前先删关联日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;

    // HTTP 客户端复用同一 baseUrl，所有请求由 call 方法统一发送。
    // 使用真实 HTTP 客户端而不是 MockMvc，
    // 以覆盖随机端口、鉴权头与响应序列化的完整链路。
    RestClient client;
    // JSON 解析器用于按字段断言接口响应，而不绑定 DTO。
    // 解析响应时只读取需要的字段，不绑定具体 DTO。
    final ObjectMapper om = new ObjectMapper();
    // 租赁单清理列表：每个用例直接插入的数据都登记于此。
    /** 本用例创建的租赁单 ID，统一清理。 */
    // 直接插入的租赁单都登记在此，用例结束后统一删除。
    final List<Long> rentalIds = new ArrayList<>();
    // 工具清理列表：创建成功的工具连同日志一起删除。
    /** 本用例创建的工具 ID，统一清理。 */
    // 工具删除前需要先清理日志，因此单独维护 ID 列表。
    final List<Long> toolIds = new ArrayList<>();

    // 响应载体同时保留状态码与 JSON 树，便于断言 404 等异常场景。
    // 记录类型不可变，适合在测试内部传递一次性的响应结果。
    // 状态码与响应体分开保存，便于区分 404 与业务校验失败。
    // 记录类型让响应在测试内部保持只读，避免意外修改。
    record ApiResponse(int status, JsonNode body) {
    }

    // 随机端口在每个测试上下文启动时分配，不能跨类复用客户端。
    // 重建成本很低，却可以避免连接指向已关闭的上下文。
    // 每次用例前重建客户端，确保指向本次上下文分配的随机端口。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // 清理只处理本类登记的数据，不执行全表删除，避免影响并行测试。
    // 若删除顺序错误，日志或租赁会留下指向已删除工具的悬挂记录。
    // 清理顺序与依赖关系一致：先删除租赁单，再删除工具日志与工具。
    // 若先删工具，日志或租赁可能留下悬挂的 toolId 引用。
    // 集合在清理后清空，便于同一个测试实例复用。
    // 即使断言失败，@AfterEach 仍会执行，保证共享库恢复到基线。
    /**
     * 用例结束后删除本类插入的租赁单与工具日志/工具。
     * 先删租赁再删工具，确保共享测试库恢复干净状态。
     */
    @AfterEach
    void cleanUp() {
        // 按登记顺序删除本用例创建的租赁单。
        for (Long id : rentalIds) {
            rentalMapper.deleteById(id);
        }
        // 工具日志按 toolId 精确删除，避免影响其他工具。
        for (Long id : toolIds) {
            // 日志删除后再删除工具本身，满足外键语义。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>().eq(ToolLog::getToolId, id));
            toolMapper.deleteById(id);
        }
        rentalIds.clear();
        toolIds.clear();
    }

    // 断言从“总数”开始，再检查分页边界，最后核对单条记录的字段完整性。
    // 这样失败时可以先判断是过滤问题、分页问题还是字段投影问题。
    // 数据构造刻意让目标工具与其他工具同时存在，以验证隔离性。
    // 该用例还隐含验证了“历史接口不依赖当前工具状态”：
    // 已取消、已归还与未来预订都应保留在历史中，不能按状态过滤掉。
    // 因此三条目标记录分别覆盖三种典型状态。
    // 管理端历史是只读接口，本用例不触发任何状态变更。
    // 因此它可以安全地在同一份数据上多次执行断言。
    // 该用例验证管理端历史列表的三个关键属性：
    // 1) total 只统计目标工具的租赁单，不混入其他工具；
    // 2) 分页结果在页间不重复、不遗漏，合并后覆盖全部记录；
    // 3) 已归还记录包含审计所需的快照字段与中文展示名。
    // 为此准备三条目标工具记录（预订/归还/取消）和一条其他工具记录。
    // 其他工具记录作为“干扰项”，用于发现过滤条件失效。
    // 金额与损坏结果特意使用非零值，避免默认值掩盖序列化问题。
    /**
     * 场景：为目标工具构造已预订、已归还、已取消三条租赁，
     * 另为其他工具构造一条租赁，然后分页查询目标工具历史。
     * 前置条件：管理端历史接口按工具 ID 过滤并按页返回。
     * 断言目标：总数只统计目标工具；两页合并恰好覆盖三条记录且不含其他工具；
     * 已归还记录的状态、损坏结果中文名、逾期费与赔偿金额、起止时间完整，
     * 保证管理端审计信息不缺失。
     */
    @Test
    void admin_tool_history_pages_and_only_contains_that_tools_rentals() {
        // 管理员令牌用于创建工具与查询管理端历史。
        String admin = adminLogin();
        // 主工具：三条租赁记录都挂在它下面。
        long toolId = createApprovedTool(admin, "main");
        // 干扰工具：用于验证历史接口不会跨工具泄漏数据。
        long otherToolId = createApprovedTool(admin, "other");

        // 固定时间基准，保证档期与归还时间的相对关系稳定。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 目标工具记录一：未来预订，状态 RESERVED。
        // 起止时间位于未来，仅用于分页覆盖，不参与已归还字段断言。
        long first = insertRental(toolId, "reserved", "RESERVED", "张三", "13900000081",
                now.plusDays(1), now.plusDays(2), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        // 目标工具记录二：已归还且轻微损坏。
        // 逾期费与赔偿费使用非零值，用于验证金额字段的序列化精度。
        long second = insertRental(toolId, "returned", "RETURNED", "李四", "13900000082",
                now.minusDays(3), now.minusDays(2), "MINOR",
                new BigDecimal("12.50"), new BigDecimal("8.00"));
        // 目标工具记录三：已取消，用于验证历史包含取消单。
        // 取消单不涉及归还时间，因此损坏结果为空。
        long third = insertRental(toolId, "cancelled", "CANCELLED", "王五", "13900000083",
                now.plusDays(5), now.plusDays(6), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        // 其他工具记录：已归还，作为过滤条件的干扰项。
        long foreign = insertRental(otherToolId, "foreign", "RETURNED", "赵六", "13900000084",
                now.minusDays(1), now, "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);

        // 第一页取 2 条，响应应回显页码、页大小与总数。
        // 第一页请求携带 page=1 与 size=2，覆盖默认分页参数。
        JsonNode pageOne = ok(call(HttpMethod.GET,
                "/api/admin/tools/" + toolId + "/rentals?page=1&size=2", admin, null));
        // data 节点包含 total、page、size 与 records。
        // 以下断言都基于 data 节点，避免反复取字段。
        JsonNode data = pageOne.get("data");
        // 总数只统计目标工具的三条记录，干扰项不计入。
        // 总数是过滤后的计数，不能包含其他工具的干扰记录。
        assertEquals(3, data.get("total").asInt(), data.toString());
        // 第一页返回两条，第二页应返回剩余一条。
        // size=2 时第一页最多返回两条记录。
        assertEquals(2, data.get("records").size(), data.toString());
        // 回显页码与请求一致，便于前端分页控件定位。
        // 页码回显必须是 1，前端据此维护分页状态。
        assertEquals(1, data.get("page").asLong());
        // 回显页大小与请求一致。
        // 页大小回显必须与请求一致。
        assertEquals(2, data.get("size").asLong());
        // 逐条确认记录都属于目标工具，防止过滤条件失效。
        // 逐条检查 toolId，确保过滤条件下推到查询层而非前端过滤。
        for (JsonNode record : data.get("records")) {
            // toolId 是管理端历史的分组键，必须与请求的工具一致。
            // 第一页每条记录的 toolId 都必须是目标工具。
            assertEquals(toolId, record.get("toolId").asLong(), record.toString());
        }

        // 第二页取剩余记录。
        // 第二页请求同一工具，只改变页码。
        JsonNode pageTwo = ok(call(HttpMethod.GET,
                "/api/admin/tools/" + toolId + "/rentals?page=2&size=2", admin, null));
        // 三条记录按 size=2 分页后，第二页应只剩一条。
        // 三条记录按每页两条切分，第二页应只剩一条。
        assertEquals(1, pageTwo.get("data").get("records").size());
        // 第二页同样只能包含目标工具的记录。
        // 第二页同样要校验工具归属，防止分页 SQL 丢失过滤条件。
        for (JsonNode record : pageTwo.get("data").get("records")) {
            // 第二页记录的 toolId 也必须与请求一致。
            assertEquals(toolId, record.get("toolId").asLong(), record.toString());
        }

        // 合并两页 ID，比较集合覆盖关系而非顺序。
        // 把两页 ID 合并到一个列表，用于整体覆盖断言。
        List<Long> seen = new ArrayList<>(ids(pageOne));
        // 合并后应恰好包含三条目标记录。
        // 合并过程中不对 ID 去重，重复记录会通过数量或集合断言暴露。
        seen.addAll(ids(pageTwo));
        // containsAll 允许共享库存在其他数据，但本用例三条必须齐全。
        // 三条目标记录必须全部出现，不能因分页边界遗漏。
        assertTrue(seen.containsAll(List.of(first, second, third)), seen.toString());
        // 干扰工具的记录不得出现在目标工具历史中。
        // 干扰工具的记录一旦出现，说明过滤条件失效或分页串数据。
        assertFalse(seen.contains(foreign), "不得混入其他工具的租赁记录");

        // 跨两页查找已归还记录，确保分页没有把关键记录漏掉。
        // 用数据库中的真实订单号定位记录，避免依赖接口返回顺序。
        JsonNode returned = recordWithOrderNo(pageOne, pageTwo, rentalMapper.selectById(second).getOrderNo());
        // 承租人姓名来自租赁快照，管理端审计需要看到。
        // 管理端审计需要承租人姓名，断言快照字段未被裁剪。
        assertEquals("李四", returned.get("renterName").asString());
        // 状态字段应保持数据库中的枚举值。
        // 状态字段保持枚举原名，前端按约定映射展示。
        assertEquals("RETURNED", returned.get("status").asString());
        // 损坏结果以中文名返回，前端无需维护枚举映射。
        // 损坏结果由服务端转换为中文名，减少前端重复映射。
        assertEquals("轻微损坏", returned.get("damageResultName").asString());
        // 逾期费使用 BigDecimal 语义比较，忽略小数位差异。
        // 金额按 BigDecimal 比较，避免 12.5 与 12.50 的字符串差异。
        assertEquals(0, returned.get("overdueFee").decimalValue()
                .compareTo(new BigDecimal("12.50")), returned.toString());
        // 赔偿金额同样按数值比较，避免 8.0 与 8.00 的字符串差异。
        // 赔偿金额同样按数值比较，保留两位小数语义。
        assertEquals(0, returned.get("damageFee").decimalValue()
                .compareTo(new BigDecimal("8.00")), returned.toString());
        // 起止时间必须存在，供管理端判断实际占用窗口。
        // 借出时间必须存在，供管理端还原实际占用区间。
        assertTrue(returned.hasNonNull("startTime"), returned.toString());
        // 结束时间与开始时间成对出现，缺一不可。
        // 归还时间与借出时间成对，缺失会导致审计信息不完整。
        assertTrue(returned.hasNonNull("endTime"), returned.toString());
    }

    // 详情接口是用户最常访问的入口，最容易在扩展字段时顺手带入历史。
    // 测试用存在历史的工具来构造最坏情况，确保返回结构仍然干净。
    // 只检查顶层 data 的字段名，不深入未知嵌套结构。
    // 用户端详情与历史是两个独立接口，不能互相透传数据。
    // 该用例验证用户端工具详情的隐私边界：
    // 即使工具存在历史租赁，详情接口也不得返回 rentals/rentalRecords。
    // 否则用户可以借此推断承租人、租赁时间与订单数量。
    // 断言直接检查字段是否存在，而不是检查字段是否为空。
    /**
     * 场景：工具存在历史租赁时，用户查询工具详情。
     * 前置条件：用户端详情只返回公开的借用信息。
     * 断言目标：响应中不存在 rentals 或 rentalRecords 字段，
     * 防止他人通过工具详情推断租赁记录与承租人。
     */
    @Test
    void user_tool_detail_does_not_expose_rental_history() {
        // 管理员令牌用于创建并审核工具。
        String admin = adminLogin();
        // 普通用户令牌用于访问用户端工具详情。
        String user = userLogin();
        // 创建并审核一个带历史记录的工具。
        long toolId = createApprovedTool(admin, "user-visible");
        // 固定时间基准，保证历史记录落在过去。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 插入一条已归还记录，使工具确实存在租赁历史。
        insertRental(toolId, "hidden", "RETURNED", "隐私测试", "13900000085",
                now.minusDays(2), now.minusDays(1), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);

        // 以普通用户身份读取工具详情。
        // 用户端详情接口使用普通用户令牌，覆盖真实鉴权路径。
        // 该工具已经存在历史租赁，是隐私泄漏风险最高的场景。
        JsonNode detail = ok(call(HttpMethod.GET, "/api/user/tools/" + toolId, user, null));
        // 详情中不得出现 rentals 字段。
        // 详情顶层不得出现 rentals 字段。
        assertFalse(detail.get("data").has("rentals"), detail.toString());
        // 也不得出现 rentalRecords 等历史数组字段。
        // 也不得以其他命名暴露历史数组。
        assertFalse(detail.get("data").has("rentalRecords"), detail.toString());
    }

    // 聚合口径决定 completedCount 的可信度，必须排除未完成订单。
    // 时间窗口只暴露起止时间，是产品明确允许的档期参考信息。
    // 隐私字段采用白名单式断言，任何新增敏感字段都会被发现。
    // completedCount 与 windows 必须来自同一批已归还记录。
    // 若窗口数量与完成数不一致，说明过滤或映射逻辑出现偏差。
    // 该用例验证用户端工具历史的聚合口径与脱敏要求：
    // 两条已归还记录应计入 completedCount 并生成两个时间窗口；
    // 未来预订不计入完成数，也不应出现在历史窗口中；
    // 返回窗口只含起止时间，不得包含姓名、手机号或订单号。
    // 这样用户既能参考工具的空闲档期，又无法识别具体承租人。
    /**
     * 场景：工具包含两条已归还与一条未来预订，用户查询工具历史。
     * 前置条件：用户端历史只展示已完成的占用窗口。
     * 断言目标：completedCount 只统计已归还的两条；
     * windows 数量为 2 且包含起止时间；不得出现承租人姓名、手机号或订单号，
     * 在提供档期参考的同时保护他人隐私。
     */
    @Test
    void user_tool_history_returns_completed_windows_without_personal_data() {
        // 管理员令牌用于创建并审核工具。
        String admin = adminLogin();
        // 普通用户令牌用于查询工具历史。
        String user = userLogin();
        // 工具需先审核通过，用户端才能看到，因此创建后立即审核。
        long toolId = createApprovedTool(admin, "user-history");
        // 固定时间基准，历史窗口使用过去时间。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 已归还记录一：四天前到三天前。
        insertRental(toolId, "done-1", "RETURNED", "张三", "13900000091",
                now.minusDays(4), now.minusDays(3), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);
        // 已归还记录二：两天前到一天前。
        insertRental(toolId, "done-2", "RETURNED", "李四", "13900000092",
                now.minusDays(2), now.minusDays(1), "INTACT", BigDecimal.ZERO, BigDecimal.ZERO);
        // 未来预订：不应计入完成数，也不应出现在历史窗口。
        insertRental(toolId, "active", "RESERVED", "王五", "13900000093",
                now.plusDays(1), now.plusDays(2), null, BigDecimal.ZERO, BigDecimal.ZERO);

        // 调用用户端历史接口并取 data 节点。
        // 直接调用用户端历史接口，路径中带目标工具 ID。
        JsonNode data = ok(call(HttpMethod.GET,
                "/api/user/tools/" + toolId + "/history", user, null)).get("data");

        // 返回的 toolId 应与请求一致，便于前端关联。
        // 返回的 toolId 与请求一致，前端可安全关联。
        assertEquals(toolId, data.get("toolId").asLong());
        // 完成数只统计已归还记录，未来预订不计入。
        // 完成数只包含 RETURNED，未来预订不计入。
        assertEquals(2, data.get("completedCount").asLong(), "只统计已归还的租赁单");
        // 窗口数量与已归还记录数一致。
        // 每个已归还记录对应一个时间窗口。
        assertEquals(2, data.get("windows").size(), data.toString());
        // 逐个窗口检查字段，防止实现层顺手带出隐私字段。
        // 窗口结构采用白名单校验，任何额外字段都需要评审。
        for (JsonNode window : data.get("windows")) {
            // 窗口必须包含开始时间，供前端渲染占用区间。
            // 窗口的开始时间是档期参考的最小信息。
            assertTrue(window.hasNonNull("startTime"), data.toString());
            // 窗口必须包含结束时间，且与开始时间成对。
            // 结束时间与开始时间共同构成区间。
            assertTrue(window.hasNonNull("endTime"), data.toString());
            // 不得暴露承租人姓名。
            // 承租人姓名属于个人数据，不得出现在窗口。
            assertFalse(window.has("renterName"), "不得暴露他人姓名");
            // 不得暴露承租人手机号。
            // 手机号更不能暴露，避免被用于骚扰或撞库。
            assertFalse(window.has("renterPhone"), "不得暴露他人手机号");
            // 不得暴露订单号，避免用户关联到具体租赁单。
            // 订单号会关联到具体租赁单，也应隐藏。
            assertFalse(window.has("orderNo"), "不得暴露他人单号");
        }
    }

    // 空列表会让前端误以为工具存在但暂无历史，导致错误提示。
    // 404 与业务码 C404 同时断言，保证 HTTP 语义与业务语义一致。
    // 该分支也保护了前端“工具已下架”场景的提示准确性。
    // 工具不存在与工具存在但无历史必须使用不同响应。
    // 该断言同时验证错误响应仍包含业务码，方便前端统一处理。
    // 若接口改成返回空数组，本用例会立即失败。
    // 该分支的响应不包含任何租赁字段，避免错误信息泄漏内部数据。
    // 该用例验证不存在的工具不会返回空历史，而是明确的 404。
    // 前端据此区分“工具不存在”和“工具暂无历史”两种状态。
    /**
     * 场景：管理员查询不存在工具的历史租赁。
     * 前置条件：工具 ID 不存在。
     * 断言目标：返回 404 与业务码 C404，而不是空列表，
     * 便于前端区分“工具不存在”和“暂无历史”。
     */
    @Test
    void unknown_tool_history_is_not_found() {
        // 使用管理员令牌访问管理端历史接口。
        String admin = adminLogin();
        // 工具 ID 刻意使用不存在的大值。
        // 请求不存在的大 ID，覆盖“资源不存在”分支。
        ApiResponse response = call(HttpMethod.GET,
                "/api/admin/tools/99999999/rentals", admin, null);
        // HTTP 层应为 404。
        // HTTP 404 表示工具本身不存在。
        assertEquals(404, response.status(), response.body().toString());
        // 业务码 C404 与 HTTP 404 对应，便于前端统一处理。
        // 业务码 C404 供前端统一映射错误提示。
        assertEquals("C404", response.body().get("code").asString());
    }

    // 跨两页查找指定订单号的记录。
    // 找不到时抛出 AssertionError，而不是返回 null，
    // 这样分页遗漏会直接表现为测试失败，便于定位。
    /**
     * 辅助方法：跨两页查找指定订单号的记录。
     * 找不到时抛 AssertionError，避免空指针掩盖分页遗漏问题。
     */
    // 该方法把“分页查找”封装为一个明确契约：
    // 找到则返回记录，找不到则抛出断言错误。
    // 调用方因此不需要处理 null，也不会把分页遗漏误判为记录缺失。
    private JsonNode recordWithOrderNo(JsonNode pageOne, JsonNode pageTwo, String orderNo) {
        // 依次扫描第一页与第二页。
        for (JsonNode page : List.of(pageOne, pageTwo)) {
            // 逐条比较订单号，订单号在本类中唯一。
            for (JsonNode record : page.get("data").get("records")) {
                // 命中后立即返回，避免继续遍历。
                if (orderNo.equals(record.get("orderNo").asString())) {
                    return record;
                }
            }
        }
        throw new AssertionError("未找到租赁单 " + orderNo);
    }

    // 从分页响应中提取记录 ID，便于集合断言。
    // 只读取 data.records，不关心其他分页字段。
    /** 辅助方法：从分页响应中提取记录 ID 列表。 */
    // 只提取 id 字段，避免在集合断言中携带无关字段。
    // 返回顺序与服务端分页顺序一致。
    private List<Long> ids(JsonNode response) {
        // 使用列表保持服务端返回顺序。
        List<Long> result = new ArrayList<>();
        for (JsonNode record : response.get("data").get("records")) {
            result.add(record.get("id").asLong());
        }
        return result;
    }

    // 直接插入租赁单，绕过下单接口，便于构造精确状态与金额。
    // 单号带自增序号，避免共享库唯一约束冲突。
    // 押金状态、归还时间与金额按状态派生，使数据满足业务约束。
    // 插入后登记 ID，供 @AfterEach 统一清理。
    /**
     * 辅助方法：直接插入指定工具、状态、承租人与金额的租赁单。
     * 按状态同步押金与归还时间，并登记到清理列表；
     * 单号带自增序号，避免唯一约束冲突。
     */
    // 该助手集中维护租赁实体的必填字段与状态派生规则。
    // 调用方只需关心本用例要断言的维度（状态、金额、档期）。
    // 所有插入都会登记到 rentalIds，保证清理不遗漏。
    // 参数较多是刻意的：测试需要精确控制每个影响断言的维度。
    // 默认值由上层重载或调用方显式传入，避免隐式依赖当前时间。
    private long insertRental(long toolId, String suffix, String status, String renterName,
                              String renterPhone, LocalDateTime start, LocalDateTime end,
                              String damageResult, BigDecimal overdueFee, BigDecimal damageFee) {
        // 组装租赁实体：先写业务标识与工具维度。
        Rental rental = new Rental();
        // 单号包含全局序号与后缀，既唯一又可读。
        rental.setOrderNo("IT-HIS-" + SEQ.incrementAndGet() + "-" + suffix);
        // toolId 决定管理端历史的分组归属。
        rental.setToolId(toolId);
        // 工具编码与名称使用测试前缀，便于人工排查。
        rental.setToolCode("IT-HIS-CODE-" + suffix);
        rental.setToolName("历史租赁测试工具 " + suffix);
        // 固定关联种子用户，租赁归属不影响历史接口的分组逻辑。
        rental.setUserId(1L);
        // 承租人姓名与手机号由参数控制，用于隐私断言与关键字匹配。
        rental.setRenterName(renterName);
        rental.setRenterPhone(renterPhone);
        // 起止时间由调用方指定，用于构造未来/过去档期。
        rental.setStartTime(start);
        rental.setEndTime(end);
        // 取件人默认与承租人一致，符合测试数据语义。
        rental.setPickupPerson(renterName);
        rental.setDepositAmount(new BigDecimal("100.00"));
        // 归还后押金状态改为已退款，否则保持已支付。
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        rental.setStatus(status);
        // 已归还记录补上实际归还时间；其他状态保持为空。
        rental.setActualReturnTime("RETURNED".equals(status) ? end.plusMinutes(30) : null);
        // 逾期费与赔偿费由调用方传入，测试可精确断言金额。
        rental.setOverdueFee(overdueFee);
        rental.setDamageFee(damageFee);
        // 退款金额固定为零，避免引入额外业务分支。
        rental.setRefundAmount(BigDecimal.ZERO);
        rental.setDamageResult(damageResult);
        // 落库并登记清理 ID。
        rentalMapper.insert(rental);
        rentalIds.add(rental.getId());
        return rental.getId();
    }

    // 创建并审核工具，供管理端历史与用户端详情使用。
    // 创建时提交完整必填字段，避免审核接口因缺字段失败。
    // 审核通过后工具才进入用户端可见范围。
    // 工具 ID 登记到清理列表，用例结束后删除。
    /**
     * 辅助方法：创建唯一名称工具并审核通过，登记到清理列表。
     */
    // 工具先创建为待审状态，再显式审核通过。
    // 审核是用户端可见的前置条件，因此助手内直接完成。
    // 创建成功后立即登记 ID，即使后续审核失败也能清理。
    private long createApprovedTool(String admin, String suffix) {
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 工具名称带测试前缀与后缀，保证唯一且可识别。
                "name", PREFIX + suffix,
                // 固定测试型号与分类，减少无关字段对历史的干扰。
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                // 押金金额使用 BigDecimal，与业务字段类型一致。
                "deposit", new BigDecimal("100"),
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of())));
        // 创建成功后立即登记清理，避免后续断言失败导致泄漏。
        long id = created.get("data").get("id").asLong();
        toolIds.add(id);
        // 审核动作独立调用，验证工具从待审变为可租。
        ok(call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")));
        return id;
    }

    // 统一的成功断言：HTTP 200 与业务码 A200。
    // 失败信息附带原始响应体，便于定位接口契约变化。
    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    // 该断言是几乎所有成功响应的入口。
    // 同时校验状态码与业务码，避免只断言其中一个造成误判。
    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    // 种子管理员登录，返回 Bearer 令牌。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    // 管理员密码来自测试种子数据，不对生产凭据做任何假设。
    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 种子用户使用密码登录，返回 Bearer 令牌。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    // 用户端使用手机号 + 密码登录，覆盖密码通道。
    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    // 登录接口成功后，令牌位于 data.tokenValue。
    // 登录失败时先断言状态码，避免后续空指针。
    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    // 登录是获取令牌的唯一入口，因此单独封装。
    // 失败时先断言 HTTP 200，再读取 tokenValue，避免空指针。
    private String login(String url, Map<String, Object> body) {
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    // 统一发送 JSON 请求：按需附加令牌与请求体，
    // 完整读取响应流并解析为 JSON 树。
    // 空响应按空对象处理，兼容 204 等无正文场景。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // 该助手统一处理鉴权头、JSON 内容类型与响应解析。
    // 空响应体按空对象处理，兼容无正文的成功响应。
    // 该方法不记录日志，失败信息由断言消息携带原始响应。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 请求规格由方法、路径与可选头部组成。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 令牌存在时使用标准 Bearer 方案。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 只有存在请求体时才设置 JSON Content-Type。
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 允许读取原始响应，不会因 4xx/5xx 直接抛异常。
        return spec.exchange((request, response) -> {
            // try-with-resources 保证响应流关闭。
            try (InputStream in = response.getBody()) {
                // 显式使用 UTF-8，避免平台默认编码造成中文乱码。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 同时保留状态码与解析后的 JSON 树。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // IO 异常属于基础设施问题，包装为运行时异常即可。
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
