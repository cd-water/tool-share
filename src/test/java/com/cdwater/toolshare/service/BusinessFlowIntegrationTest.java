// 主业务流程集成测试：覆盖工具从创建到归还的完整闭环。
// 流程依次经过创建、审核、预约、支付、取件、报修、维修、归还与验收。
// 同时覆盖文件上传读取、知识库增删、档期冲突取消与 AI 流式对话。
// 测试通过随机端口访问真实 HTTP 接口，使用测试数据库与内存对象存储。
// 种子账号提供用户与管理员两种身份，覆盖双端权限边界。
// 核心不变量是状态机顺序、押金退款金额与工具状态恢复时机。
// 每个用例按统一前缀清理数据，避免主流程断言受残留影响。
// 文件内注释只说明业务语义与断言意图，不改变任何代码行。
package com.cdwater.toolshare.service;

// MyBatis-Plus 条件构造器用于按工具或标题前缀清理测试数据。
// 基类负责数据库迁移、容器生命周期与测试隔离。
// 内存存储配置让上传与读取走真实文件链路而不依赖外部服务。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
// 报修实体与租赁、工具实体是主流程断言的核心对象。
// 工具日志用于验证清理顺序，避免删除工具后留下孤儿记录。
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
// 各模块 Mapper 用于直接核对数据库状态与执行清理。
// 通过 Mapper 绕过 HTTP 层，便于在流程中修改档期等测试前置。
// 知识库 Mapper 只用于清理上传的测试文档。
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
// JUnit 生命周期注解负责创建客户端与清理数据。
// Spring 测试注解启动随机端口的完整应用。
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// 随机端口与依赖注入支撑真实 HTTP 调用。
// 导入内存存储配置，保证上传用例可以独立运行。
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
// HTTP 客户端与 multipart 构造器用于上传用例。
// ByteArrayResource 模拟浏览器上传的文件内容。
// 响应类型与状态枚举用于断言上传与流式接口。
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
// Jackson 树模型用于按业务码与 data 字段断言响应。
// ObjectMapper 由测试自行创建，避免依赖应用内实例。
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// ImageIO 与 BufferedImage 用于生成并解码真实 PNG。
// 字节流与字符集工具用于构造上传内容。
// 时间与集合类型用于构造预约档期与请求体。
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
// 时间格式化器生成接口约定的本地日期时间字符串。
// List 与 Map 用于构造 JSON 请求体。
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// 断言业务码、状态、金额与响应内容。
// assertNotNull 用于确认图片可被解码。
// 断言失败信息会带上响应体，便于定位流程断点。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主业务流程集成测试。
 * <p>
 * 测试范围：工具创建与审核、预约、支付、取件、报修、维修进度、带伤归还与验收，
 * 以及文件上传读取、知识库增删、档期冲突取消和 AI 流式对话。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子账号
 * （用户 13800000001/Aa123456、管理员 admin/Aa123456）。
 * 关键不变量：主流程各状态按状态机顺序推进；押金与退款金额一致；
 * 报修未完成时必须带伤归还且工具保持维修中；验收合格后才恢复可用；
 * 冲突预约返回 C409，取消后押金全额退还。
 * 补充说明：核心用例走完租赁与报修两条状态机。
 * 租赁线：待支付、已支付、取件、归还、退款。
 * 报修线：提交、确认、维修中、完成、验收。
 * 两条线在带伤归还与验收处交汇，工具状态必须一致。
 * 文件上传与知识库用例覆盖对象存储与文档管理。
 * 冲突取消用例验证档期互斥与押金全额退款。
 * AI 流式用例验证 SSE 契约与登录保护。
 * 辅助方法统一封装 HTTP 调用，减少断言样板。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class BusinessFlowIntegrationTest extends AbstractIntegrationTest {

    // 时间格式与接口入参保持一致，秒级精度避免数据库截断差异。
    // 统一前缀用于按名称或标题精确清理本类数据。
    // 前缀同时出现在工具名与知识库标题中。
    // 所有测试数据都带前缀，防止误删其它用例记录。
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-集成测试-";

    // 随机端口由 Spring Boot 注入，HTTP 客户端据此访问应用。
    // 使用随机端口可以避免并行测试时的端口冲突。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    @Autowired
    // 工具 Mapper 用于按名称前缀查询并清理测试工具。
    /** 工具 Mapper，用于清理测试工具。 */
    ToolMapper toolMapper;

    @Autowired
    // 租赁 Mapper 用于清理工具关联的租赁单。
    /** 租赁单 Mapper，用于调整档期与清理。 */
    RentalMapper rentalMapper;

    @Autowired
    // 报修 Mapper 用于清理工具关联的报修单。
    /** 报修 Mapper，用于清理测试报修。 */
    DamageReportMapper damageReportMapper;

    @Autowired
    // 工具日志 Mapper 用于在删除工具前清理审计日志。
    /** 工具日志 Mapper，清理工具前先删日志。 */
    ToolLogMapper toolLogMapper;

    @Autowired
    // 知识库 Mapper 用于清理上传的测试文档。
    /** 知识库 Mapper，用于清理测试文档。 */
    KnowledgeDocMapper knowledgeDocMapper;

    // HTTP 客户端在每个用例前绑定随机端口。
    // 它复用 Spring 上下文的 Web 链路，覆盖过滤器与鉴权。
    // 客户端不缓存会话，令牌通过请求头显式传递。
    RestClient client;
    // 树模型解析器用于统一读取业务码与 data 字段。
    // 空响应会被辅助方法处理成空对象，避免解析异常。
    final ObjectMapper om = new ObjectMapper();

    // 每个用例重新创建客户端，保证端口绑定正确。
    // 客户端构建成本很低，不需要跨用例复用。
    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // 使用随机端口构造基础地址。
        // 后续请求都通过该客户端发送，自动走真实 Web 层。
        // 这样鉴权、参数校验与异常处理都与生产一致。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后按名称/标题前缀清理测试工具、报修、租赁、日志与知识库文档。
     * 先删关联业务数据再删工具，避免残留数据干扰主流程断言。
     * 清理顺序先报修、租赁、日志，再删工具本身。
     * 这样不会留下指向已删工具的关联记录。
     * 查询按名称前缀匹配，只处理本类创建的工具。
     * 知识库文档独立按标题前缀删除。
     * 清理在断言之后执行，失败时也不会阻断后续用例。
     */
    @AfterEach
    void cleanUp() {
        // 查询本类前缀创建的工具，逐个清理关联数据。
        // likeRight 生成前缀匹配，避免全表扫描后误删。
        // 工具名称前缀与创建方法保持一致。
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                // 前缀匹配只命中本类工具。
                .likeRight(Tool::getName, PREFIX));
        // 逐个工具删除依赖数据，保证外键与业务一致性。
        for (Tool tool : tools) {
            // 先删除该工具的报修单。
            // 报修引用租赁与工具，必须先于两者清理。
            // 条件按工具 ID 精确匹配。
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            // 再删除该工具的租赁单。
            // 租赁引用工具，删除工具前必须清空。
            // 条件按工具 ID 精确匹配。
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            // 然后删除工具日志。
            // 日志记录状态迁移，工具删除后不应继续保留。
            // 条件按工具 ID 精确匹配。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 关联数据清空后再删除工具本身。
            // 按主键删除，范围精确。
            toolMapper.deleteById(tool.getId());
        }
        // 按标题前缀清理知识库测试文档。
        // 知识库与工具没有关联，可独立删除。
        // 前缀与上传用例使用的标题一致。
        knowledgeDocMapper.delete(new LambdaQueryWrapper<KnowledgeDoc>()
                // 标题前缀匹配，只删除本类上传的文档。
                .likeRight(KnowledgeDoc::getTitle, PREFIX));
    }

    /**
     * 场景：完整走一遍工具创建、预约、支付、取件、报修、维修、带伤归还与验收。
     * 前置条件：工具已审核，用户已登录，租赁档期在未来。
     * 断言目标：订单号与报修单号符合前缀规则；状态依次推进；
     * 报修确认后工具进入维修中；带伤归还后退款为押金减维修费；
     * 只有维修完成进度后才能验收，验收合格后工具恢复可用；
     * 全流程会向用户投递站内消息。
     * 补充说明：用例同时验证订单号与报修单号的编码规则。
     * 支付与取件由不同角色完成，覆盖双端接口。
     * 报修确认后工具必须进入维修中，避免继续被预约。
     * 带伤归还时退款额等于押金减维修费。
     * 维修完成前不能验收，保证状态机顺序。
     * 验收合格后工具才恢复可用，消息通知也应产生。
     */
    @Test
    void rental_and_damage_lifecycle() {
        // 分别获取管理员与用户令牌，后续按角色调用接口。
        // 管理员负责审核、取件、报修处理与验收。
        // 用户负责预约、支付、报修与查询。
        // 两种身份贯穿整个生命周期用例。
        String adminToken = adminLogin();
        String userToken = userLogin();
        // 创建并审核一个押金 120 元的测试工具。
        // 工具编号由后端生成，辅助方法会校验编码格式。
        // 审核通过后工具才允许被用户看到和预约。
        // 工具名称带统一前缀，便于结束时清理。
        long toolId = createTool(adminToken, "生命周期", "120");
        // 选择两天后的档期，避免与库内既有租赁冲突。
        // 开始与结束时间都去掉纳秒，匹配接口秒级格式。
        // 档期跨越一天，足够覆盖取件与归还流程。
        String start = LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT);

        // 用户创建预约，初始状态应为待支付。
        // 请求体包含工具、档期与取件人。
        // 服务端会检查工具审核状态与档期冲突。
        // 创建成功后返回订单号与租赁 ID。
        // 该调用是租赁状态机的入口。
        JsonNode created = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        // 断言预约成功，并保留完整响应便于失败排查。
        // 业务码 A200 表示请求被正常处理。
        // 后续断言依赖响应中的 data 节点。
        assertEquals("A200", created.get("code").asString(), created.toString());
        long rentalId = created.get("data").get("id").asLong();
        // 新预约必须处于待支付状态。
        // 状态字段是后续支付与取件操作的前置条件。
        // 若状态不符，说明创建逻辑或状态机初始化有误。
        assertEquals("PENDING_PAYMENT", created.get("data").get("status").asString());
        assertTrue(created.get("data").get("orderNo").asString().startsWith("SQ-ZY-"));

        // 用户支付押金，推动状态从待支付进入已支付。
        // 支付成功后管理员才能执行取件。
        // 这里只断言业务码，金额由后续退款断言覆盖。
        // 支付接口使用空请求体，费用由服务端计算。
        assertEquals("A200", post("/api/user/rentals/" + rentalId + "/pay", userToken, Map.of())
                .get("code").asString());
        // 管理员确认取件，租赁进入使用中。
        // 取件是报修与归还的前置状态。
        // 只有已支付订单才允许取件。
        assertEquals("A200", post("/api/admin/rentals/" + rentalId + "/pickup", adminToken, Map.of())
                .get("code").asString());
        // 用户查询租赁详情，确认状态已变为 PICKED_UP。
        // 该状态表示工具已交付，用户可发起报修。
        // 查询走用户端鉴权，确保数据归属正确。
        assertEquals("PICKED_UP", get("/api/user/rentals/" + rentalId, userToken)
                .get("data").get("status").asString());

        // 用户提交报修，描述电源接口损坏。
        // 报修绑定当前租赁单，必须处于使用中状态。
        // 图片列表为空，聚焦报修状态流转。
        // 报修单号由服务端生成并带业务前缀。
        // 提交后报修进入待审核状态。
        JsonNode report = post("/api/user/damage-reports", userToken, Map.of(
                "rentalId", rentalId,
                "damagePart", "电源接口",
                "description", "通电后无反应",
                "images", List.of()));
        // 断言报修提交成功，并保留响应用于定位问题。
        // 报修 ID 是后续确认、进度与验收的入口。
        // 业务码与报修单号共同证明记录已创建。
        assertEquals("A200", report.get("code").asString(), report.toString());
        long reportId = report.get("data").get("id").asLong();
        // 报修单号必须符合 SQ-BX- 前缀规则。
        // 该规则用于前端展示与运维检索。
        // 编号格式错误会影响业务可追溯性。
        assertTrue(report.get("data").get("reportNo").asString().startsWith("SQ-BX-"));

        // 管理员确认报修，填写维修人与预估工时。
        // 确认后报修进入维修流程，工具应被锁定。
        // 确认是维修进度与验收的前置步骤。
        // 备注记录故障判断，供后续追溯。
        // 该操作推动报修状态机前进。
        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/verify", adminToken,
                Map.of("verifyResult", "CONFIRMED", "repairPerson", "王师傅",
                        "repairEstimate", "2 天", "remark", "确认线路故障")).get("code").asString());
        // 报修确认后工具必须进入维修中。
        // 维修中的工具不能被用户继续预约。
        // 这是报修与工具状态联动的关键不变量。
        // 查询走用户端，确认对外可见状态一致。
        assertEquals("MAINTENANCE", get("/api/user/tools/" + toolId, userToken)
                .get("data").get("status").asString());

        // 管理员记录维修进度，表示正在维修。
        // 进度记录维修成本，后续退款会扣除该费用。
        // 维修中仍是未终结状态，工具保持锁定。
        // 进度与备注用于向用户展示处理过程。
        // 该步骤为带伤归还做准备。
        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/progress", adminToken,
                Map.of("progressStatus", "REPAIRING", "progressNote", "已更换电源线",
                        "repairCost", 30)).get("code").asString());
        // 报修未终结时应带伤归还，工具保持维修中。
        // 带伤归还不修复工具，只结束租赁占用。
        // 工具状态的最终决定权留给维修验收环节。
        // 报修未终结时应带伤归还，工具保持维修中，待验收后再决定是否恢复可用。
        // 报修未终结时按「带伤归还」处理，工具保持维修中
        // 读取租赁单并调整开始时间到过去。
        // 这样管理员可以立即执行归还，不受未来档期限制。
        // 直接通过 Mapper 修改，模拟租期已经结束。
        // 该调整只影响归还前置条件，不改变业务状态字段。
        Rental toReturn = rentalMapper.selectById(rentalId);
        toReturn.setStartTime(LocalDateTime.now().minusDays(1).withNano(0));
        rentalMapper.updateById(toReturn);
        // 管理员执行带伤归还，声明损坏严重。
        // 归还时间取当前时刻，保持秒级精度。
        // returnWithDamage=true 表示工具带伤返回。
        // 服务端会结算退款并保留维修中的工具状态。
        // 该操作结束租赁线，但报修线仍在进行。
        assertEquals("A200", post("/api/admin/rentals/" + rentalId + "/return", adminToken,
                Map.of("actualReturnTime", LocalDateTime.now().withNano(0).format(FORMAT),
                        "damageResult", "SEVERE", "returnWithDamage", true,
                        "remark", "带伤归还")).get("code").asString());
        // 查询归还后的租赁详情，核对状态与退款结果。
        // 退还状态、押金状态与退款金额三者应一致。
        // 退款金额等于押金减去已记录维修费。
        // 这些字段共同证明结算逻辑正确。
        JsonNode returned = get("/api/user/rentals/" + rentalId, userToken).get("data");
        // 租赁状态应变为已归还。
        // 该状态表示租赁线已经结束。
        assertEquals("RETURNED", returned.get("status").asString());
        // 押金状态应为已退款。
        // 带伤归还仍需按结算结果退还剩余押金。
        assertEquals("REFUNDED", returned.get("depositStatus").asString());
        // 退款额为 120 元押金减 30 元维修费，即 70 元。
        // 金额断言直接固化结算规则。
        // 若维修费未扣除，用户会获得超额退款。
        assertEquals(70, returned.get("refundAmount").asInt());

        // 验收前必须先把维修进度推进到完成。
        // 完成进度是验收状态机的前置条件。
        // 跳过该步骤直接验收应被服务端拒绝。
        // 验收前必须先把维修进度推进到 COMPLETED，保证流程顺序。
        // 必须记录「维修完成」进度后才能验收
        // 管理员记录维修完成，工具仍处于维修中。
        // 完成进度仅表示维修工作结束，尚未验收。
        // 此时报修单进入待验收状态。
        // 维修成本保持 30 元，与退款计算一致。
        // 下一步才能执行验收。
        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/progress", adminToken,
                Map.of("progressStatus", "COMPLETED", "progressNote", "维修完成",
                        "repairCost", 30)).get("code").asString());
        // 验收合格后工具才恢复可用。
        // 归还线与报修线在此处同时闭环。
        // 验收结果决定工具最终状态。
        // 验收合格后工具才恢复 AVAILABLE，归还与报修两条线同时闭环。
        // 维修验收通过后工具才恢复可用（归还已结束、报修已终结）
        // 管理员执行验收，结果合格且工具恢复可用。
        // toolStatusAfter 指定验收后的目标状态。
        // 只有维修完成的报修才允许验收。
        // 验收后报修单进入终态，不再参与待处理统计。
        // 该操作完成报修线的最后一步。
        assertEquals("A200", post("/api/admin/damage-reports/" + reportId + "/accept", adminToken,
                Map.of("acceptResult", "QUALIFIED", "toolStatusAfter", "AVAILABLE",
                        "remark", "恢复正常")).get("code").asString());
        // 报修单状态应变为已完成。
        // 这是报修状态机的终态。
        // 查询带管理员令牌，返回内部完整字段。
        assertEquals("COMPLETED", get("/api/admin/damage-reports/" + reportId, adminToken)
                .get("data").get("status").asString());
        // 工具状态应恢复为可用。
        // 恢复可用意味着用户可以再次预约该工具。
        // 该断言证明验收结果已同步到工具主数据。
        assertEquals("AVAILABLE", get("/api/user/tools/" + toolId, userToken)
                .get("data").get("status").asString());
        // 全流程应产生站内消息通知用户。
        // 消息总数大于零即可证明通知链路被触发。
        // 查询走用户端分页接口，验证消息归属。
        // 具体消息内容由消息模块测试覆盖。
        assertTrue(get("/api/user/messages?page=1&size=20", userToken)
                .get("data").get("total").asInt() > 0);
    }

    /**
     * 场景：用户上传真实 PNG 图片，再通过返回的直链读取。
     * 前置条件：内存对象存储启用，上传接口按真实图片类型校验。
     * 断言目标：上传返回 A200 与受控域名 URL；读取响应 Content-Type 为 PNG，
     * 内容可被 ImageIO 解码，证明重编码后的文件有效。
     * 补充说明：上传内容由测试现场生成的真实 PNG。
     * 服务端会校验图片类型并重新编码后写入对象存储。
     * 上传响应返回受控域名下的直链。
     * 读取直链时应返回 PNG 内容类型。
     * 内容必须能被 ImageIO 解码，证明文件有效。
     */
    @Test
    void upload_and_read_file() throws Exception {
        // 获取用户令牌，上传接口需要登录身份。
        // 用户令牌走与前端一致的鉴权链路。
        // 上传接口按用户维度做权限校验。
        String userToken = userLogin();
        // 生成 16x16 的真实 RGB 图片。
        // 使用 PNG 编码写入字节数组，模拟用户上传。
        // 真实图片可以通过服务端的类型嗅探与重编码。
        // 尺寸足够小，避免测试数据过大。
        // ImageIO.write 的返回值用于确认编码成功。
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        // 构造 multipart 请求体，字段名为 file。
        // 文件名包含中文，验证服务端对文件名的处理。
        // 内容类型声明为 PNG，与服务端校验一致。
        // ByteArrayResource 提供文件字节流。
        // multipart 结构与浏览器上传一致。
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(out.toByteArray()))
                .filename("测试-集成.png")
                .contentType(MediaType.IMAGE_PNG);

        // 调用用户上传接口，携带 Bearer 令牌。
        // 请求体为 multipart，内容类型由构造器设置。
        // 响应以字符串接收，便于统一解析业务码。
        // 上传成功后会写入内存对象存储。
        // 返回的直链指向受控测试域名。
        String raw = client.post().uri("/api/user/upload")
                .header("Authorization", "Bearer " + userToken)
                .body(body.build())
                .retrieve().body(String.class);
        // 解析上传响应，空响应回退为空对象。
        // 避免服务端异常返回空体时解析器直接抛错。
        // 后续按业务码与 data.url 断言。
        // 空对象会让断言给出更清晰的失败信息。
        JsonNode node = om.readTree(raw == null ? "{}" : raw);
        // 断言上传返回成功业务码。
        // 失败信息包含完整响应，便于定位上传问题。
        // 通过后才能从 data 中提取直链。
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 提取服务端返回的对象直链。
        // 直链前缀由测试存储配置决定。
        // 后续会校验域名并截取对象键。
        String url = node.get("data").get("url").asString();
        // 直链必须使用受控测试域名。
        // 域名之外的地址可能指向外部资源。
        // 该断言同时保护后续键截取逻辑。
        assertTrue(url.startsWith("https://files.test/tool-share/"));

        // 从直链中截取对象键，用于调用读取接口。
        // 键的格式由存储配置决定，测试不硬编码具体值。
        // 截取依赖固定域名前缀，前缀已在上一断言校验。
        // 读取走公开文件接口，不需要令牌。
        String key = url.substring("https://files.test/tool-share/".length());
        // 通过公开文件接口读取对象，不需要令牌。
        // 读取地址由受控域名与对象键拼接而成。
        // 该步骤验证上传后的文件确实可被访问。
        ResponseEntity<byte[]> read = client.get().uri("/api/files/" + key)
                .retrieve().toEntity(byte[].class);
        // 响应类型应为 image/png。
        // 内容可被 ImageIO 解码，证明重编码后的文件有效。
        // 读取接口与上传接口共同构成对象存储闭环。
        // 两个断言分别验证响应元数据与文件内容。
        assertEquals(MediaType.IMAGE_PNG, read.getHeaders().getContentType());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(read.getBody())));
    }

    /**
     * 场景：管理员上传文本知识库文档，查询列表确认存在，再删除并复查。
     * 前置条件：管理员已登录，文件为纯文本。
     * 断言目标：上传成功返回 ID；列表包含文档标题；删除成功且列表不再包含，
     * 覆盖知识库的最小增删闭环。
     * 补充说明：知识库文档由管理员上传，用户端可检索。
     * 上传使用 multipart 表单，包含文件与标题字段。
     * 列表接口应能查到新文档标题。
     * 删除后列表不再包含该标题。
     * 用例覆盖文档管理的最小增删闭环。
     */
    @Test
    void knowledge_upload_and_delete() {
        // 获取管理员令牌，知识库管理仅管理员可用。
        // 用户端只读，管理端负责上传与删除。
        // 该权限边界由接口鉴权保证。
        String adminToken = adminLogin();
        // 构造纯文本知识库文件与标题字段。
        // 文件内容为中文业务说明，验证编码处理。
        // 标题带统一前缀，便于列表断言与清理。
        // multipart 同时包含文件与普通表单字段。
        // 文本类型与真实知识库文档一致。
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(
                "集成测试知识库内容：租赁工具需按时归还，损坏需报修。".getBytes(StandardCharsets.UTF_8)))
                .filename("测试-知识库.txt")
                .contentType(MediaType.TEXT_PLAIN);
        body.part("title", PREFIX + "知识库");

        // 调用管理员知识库上传接口。
        // 请求携带 Bearer 令牌与 multipart 表单。
        // 上传成功返回文档 ID。
        // 该 ID 用于后续删除操作。
        // 响应以字符串接收后统一解析。
        String raw = client.post().uri("/api/admin/knowledge")
                .header("Authorization", "Bearer " + adminToken)
                .body(body.build())
                .retrieve().body(String.class);
        // 解析上传响应，空体回退为空对象。
        // 业务码确认上传成功。
        // data.id 是删除接口的路径参数。
        // 断言失败信息包含完整响应。
        JsonNode node = om.readTree(raw == null ? "{}" : raw);
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 提取文档 ID，供删除步骤使用。
        // 该 ID 由数据库生成，测试不假设具体值。
        long id = node.get("data").get("id").asLong();

        // 查询知识库列表，确认新文档标题出现。
        // data 被转成字符串后做包含判断。
        // 这样可以忽略分页结构差异，聚焦文档存在性。
        // 标题前缀保证只匹配本用例上传的文档。
        assertTrue(get("/api/admin/knowledge", adminToken).get("data").toString().contains(PREFIX + "知识库"));
        // 调用删除接口移除刚上传的文档。
        // 删除按文档 ID 精确执行。
        // 业务码确认删除成功。
        assertEquals("A200", delete("/api/admin/knowledge/" + id, adminToken).get("code").asString());
        // 再次查询列表，确认标题已消失。
        // 该断言闭合知识库增删链路。
        // 若仍存在，说明删除未真正生效。
        // 列表查询与上传使用同一管理员身份。
        assertFalse(get("/api/admin/knowledge", adminToken).get("data").toString().contains(PREFIX + "知识库"));
    }

    /**
     * 场景：同一工具同一档期创建两笔预约，再取消第一笔。
     * 前置条件：第一笔已支付押金。
     * 断言目标：第二笔返回 C409 冲突；取消后状态为 CANCELLED、
     * 押金 REFUNDED 且退款金额非空，保证冲突与退款语义正确。
     * 补充说明：第一笔预约已支付，占用目标档期。
     * 第二笔相同档期预约应返回 C409 冲突。
     * 冲突码用于前端提示用户重新选择时间。
     * 取消第一笔后押金应全额退还。
     * 取消状态与退款金额共同验证取消语义。
     */
    @Test
    void conflict_and_cancel() {
        // 获取双端令牌，用户负责预约与取消。
        // 管理员负责创建并审核工具。
        // 两种身份覆盖冲突场景的完整接口链路。
        // 令牌在后续所有请求中复用。
        String adminToken = adminLogin();
        String userToken = userLogin();
        // 创建并审核一个押金 80 元的工具。
        // 审核通过后才允许用户预约。
        // 工具名称带统一前缀便于清理。
        long toolId = createTool(adminToken, "冲突", "80");
        // 选择四天后的档期，避免与其它用例冲突。
        // 开始与结束时间保持秒级精度。
        String start = LocalDateTime.now().plusDays(4).withNano(0).format(FORMAT);
        String end = LocalDateTime.now().plusDays(5).withNano(0).format(FORMAT);

        // 创建第一笔预约，占用目标工具与档期。
        // 预约成功后进入待支付状态。
        // 该订单用于制造档期冲突。
        // 响应中的租赁 ID 供后续支付与取消使用。
        // 创建请求与正常预约完全一致。
        JsonNode first = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        // 断言第一笔预约创建成功。
        // 业务码 A200 表示档期可用。
        // 失败信息包含完整响应便于排查。
        assertEquals("A200", first.get("code").asString(), first.toString());
        long rentalId = first.get("data").get("id").asLong();
        // 支付第一笔预约，确保其真正占用档期。
        // 已支付订单是冲突判断的有效占用。
        // 这里只关心占用效果，不断言支付响应。
        post("/api/user/rentals/" + rentalId + "/pay", userToken, Map.of());

        // 使用完全相同的工具与档期创建第二笔预约。
        // 服务端应检测到档期重叠并拒绝。
        // 该请求用于验证互斥规则。
        // 用户与取件人保持不变，排除其它变量。
        // 冲突判断应发生在创建阶段。
        JsonNode conflict = post("/api/user/rentals", userToken,
                Map.of("toolId", toolId, "startTime", start, "endTime", end, "pickupPerson", "王建国"));
        // 第二笔预约必须返回 C409 冲突。
        // 该业务码表示资源档期已被占用。
        // 若返回成功，说明互斥检查失效。
        assertEquals("C409", conflict.get("code").asString());

        // 用户取消第一笔已支付预约。
        // 取消原因由请求体传入。
        // 服务端应释放档期并触发押金退款。
        // 取消是租赁状态机的终态迁移。
        // 业务码确认取消操作成功。
        assertEquals("A200", post("/api/user/rentals/" + rentalId + "/cancel", userToken,
                Map.of("reason", "临时有事")).get("code").asString());
        // 查询取消后的租赁详情。
        // 状态应为已取消，押金应已退款。
        // 退款金额字段必须非空。
        // 三个断言共同验证取消语义。
        JsonNode cancelled = get("/api/user/rentals/" + rentalId, userToken).get("data");
        assertEquals("CANCELLED", cancelled.get("status").asString());
        assertEquals("REFUNDED", cancelled.get("depositStatus").asString());
        assertFalse(cancelled.get("refundAmount").isNull());
    }

    /**
     * 场景：登录用户请求 AI 流式对话，再以匿名身份请求同一接口。
     * 前置条件：流式接口返回 SSE，AI 不可用时应仍能返回来源事件与数据帧。
     * 断言目标：HTTP 200、Content-Type 为 text/event-stream，
     * 响应包含 sources 事件与 data 帧；匿名请求返回 C401，
     * 保证流式对话也受登录保护。
     * 补充说明：流式接口返回 SSE，而不是普通 JSON。
     * AI 不可用时仍应返回来源事件与数据帧。
     * sources 事件用于前端展示知识来源。
     * data 帧承载回答内容或降级提示。
     * 匿名请求必须被拒绝，保证登录保护一致。
     */
    @Test
    void ai_stream_endpoint() {
        // 获取用户令牌，流式对话需要登录身份。
        // 匿名场景随后单独验证。
        // 令牌通过 Authorization 头传递。
        String userToken = userLogin();
        // 调用 AI 流式对话接口，声明接受事件流。
        // 请求体包含自然语言问题。
        // 响应以实体形式接收，便于同时检查状态与响应体。
        // 内容类型为 JSON，与前端调用一致。
        // 流式接口不应阻塞到完整回答生成。
        var entity = client.post().uri("/api/user/ai/chat/stream")
                .header("Authorization", "Bearer " + userToken)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "押金什么时候退还？"))
                .retrieve()
                .toEntity(String.class);
        // 断言 HTTP 状态为 200。
        // 流式接口即使降级也应返回成功状态。
        // 失败会导致前端无法建立事件流。
        // 状态码与业务码在流式响应中分开处理。
        assertEquals(HttpStatus.OK, entity.getStatusCode());
        // 响应内容类型必须是 text/event-stream。
        // 该类型决定浏览器使用 EventSource 解析。
        // 若退回 JSON，前端流式展示会失效。
        assertTrue(entity.getHeaders().getContentType().toString().contains("text/event-stream"));
        // 读取响应体，空体回退为空字符串。
        // 后续断言在原始文本中查找事件与数据帧。
        // 空体也应给出清晰失败信息。
        String body = entity.getBody() == null ? "" : entity.getBody();
        // 响应应包含 sources 事件。
        // 不同序列化方式可能在冒号后带空格，因此两种写法都接受。
        // 来源事件让用户了解回答依据。
        // 该断言验证 SSE 帧格式正确。
        assertTrue(body.contains("event:sources") || body.contains("event: sources"), body);
        assertTrue(body.contains("data:"), body);

        // 匿名请求同一流式接口，验证登录保护。
        // 不携带 Authorization 头。
        // 请求体仍为合法 JSON，排除参数错误干扰。
        // 预期鉴权层在进入业务逻辑前拒绝。
        // 该场景防止未登录用户消耗 AI 资源。
        JsonNode unauthorized = post("/api/user/ai/chat/stream", null, Map.of("question", "押金"));
        // 匿名请求必须返回 C401。
        // 该业务码表示未认证。
        // 若返回成功，说明流式接口遗漏了登录校验。
        assertEquals("C401", unauthorized.get("code").asString());
    }

    /**
     * 辅助方法：创建并审核通过一个工具，返回工具 ID。
     * 同时断言工具编号符合 SQ-G-WX-四位数字的生成规则。
     * 该辅助方法同时校验后端生成的工具编号格式。
     * 编号规则为 SQ-G-分类-四位序号。
     * 创建后立即审核通过，返回可用工具 ID。
     * 押金以字符串传入，辅助方法内转换为整数。
     */
    private long createTool(String token, String suffix, String deposit) {
        // 调用管理员创建工具接口。
        // 工具名称带统一前缀，便于清理。
        // 分类固定为 WX，编号规则断言依赖该分类。
        // 押金按调用方传入的字符串转换。
        // 图片列表为空，聚焦工具主数据。
        JsonNode node = post("/api/admin/tools", token, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", Integer.parseInt(deposit),
                "storageArea", "测试柜",
                "usageGuide", "测试说明",
                "imageUrls", List.of()));
        // 断言工具创建成功。
        // 失败信息包含完整响应，便于定位字段校验问题。
        // 创建成功后返回工具 ID 与编号。
        assertEquals("A200", node.get("code").asString(), node.toString());
        // 工具编号必须符合 SQ-G-WX-四位数字规则。
        // 编号用于展示与检索，格式错误会影响前端展示。
        // 该断言覆盖分类拼接与序号补零。
        assertTrue(node.get("data").get("code").asString().matches("SQ-G-WX-\\d{4}"));
        long id = node.get("data").get("id").asLong();
        // 立即审核通过，使工具对用户可见并可预约。
        // 审核备注记录测试意图。
        // 审核状态迁移会写入审计日志。
        JsonNode audit = patch("/api/admin/tools/" + id + "/audit", token,
                Map.of("auditStatus", "APPROVED", "remark", "测试通过"));
        assertEquals("A200", audit.get("code").asString(), audit.toString());
        return id;
    }

    /**
     * 辅助方法：发送 JSON PATCH 请求并解析响应。
     * 不因 4xx 抛异常，便于调用方统一断言业务码。
     * 该辅助方法用于 PATCH 类接口。
     * 不因 4xx 抛异常，调用方统一断业务码。
     * 便于测试需修改审核等预期失败场景。
     */
    private JsonNode patch(String url, String token, Object body) {
        // 构造 PATCH 请求并指定 JSON 内容类型。
        // 目标地址由调用方传入。
        // 方法名与接口动作保持一致。
        // 请求体由调用方提供。
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.PATCH)
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        // 有令牌时添加 Bearer 鉴权头。
        // 匿名请求则跳过该头，用于验证未登录行为。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 发送请求并接受所有状态码，避免 4xx 抛异常。
        // 空响应会回退为空对象，保证解析安全。
        // 统一返回树模型供调用方断言。
        // 该处理方式适合业务码驱动的错误语义测试。
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    // 使用种子用户手机号与密码登录。
    // 登录成功返回令牌，供后续用户端请求使用。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        // 调用用户密码登录接口。
        // 请求体包含手机号与密码。
        // 种子账号在测试启动时写入。
        JsonNode node = post("/api/user/login/by-pwd", null,
                Map.of("phone", "13800000001", "password", "Aa123456"));
        // 断言登录成功。
        // 失败信息包含响应，便于排查种子数据或密码变更。
        // 成功响应中的 tokenValue 即后续令牌。
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    // 使用种子管理员账号与密码登录。
    // 管理员端使用用户名而不是手机号。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        // 调用管理员登录接口。
        // 请求体字段为 username 与 password。
        // 管理员令牌用于审核、取件与报修处理。
        JsonNode node = post("/api/admin/login", null,
                Map.of("username", "admin", "password", "Aa123456"));
        // 断言管理员登录成功。
        // 失败通常意味着种子账号或密码配置有误。
        // tokenValue 供管理员端请求使用。
        assertEquals("A200", node.get("code").asString(), node.toString());
        return node.get("data").get("tokenValue").asString();
    }

    /**
     * 辅助方法：发送 JSON POST 请求并解析响应。
     * 兼容空响应体，避免无内容响应导致解析失败。
     * 该辅助方法用于 JSON POST 请求。
     * 兼容空响应体，避免无内容响应解析失败。
     * 调用方按业务码断言成功或失败。
     */
    private JsonNode post(String url, String token, Object body) {
        // 构造 POST 请求并指定 JSON 内容类型。
        // 目标地址与请求体由调用方传入。
        // 方法封装了上传之外的大部分写操作。
        // 统一解析方式降低断言样板。
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            // 有令牌时添加 Bearer 鉴权头。
            // 匿名请求跳过鉴权头。
            spec.header("Authorization", "Bearer " + token);
        }
        // 发送请求并接受所有状态码。
        // 空响应回退为空对象，保证解析安全。
        // 返回树模型供调用方按业务码断言。
        // 该封装覆盖成功与预期失败两类场景。
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    // 发送可带令牌的 GET 请求。
    // 用于查询列表、详情与日志。
    /** 辅助方法：发送可带令牌的 GET 请求并解析响应。 */
    private JsonNode get(String url, String token) {
        // 构造 GET 请求并绑定目标地址。
        // GET 没有请求体，只需处理鉴权头。
        // 响应统一解析为树模型。
        // 查询类请求常用于验证状态推进结果。
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
        // 有令牌时添加 Bearer 鉴权头。
        // 匿名查询用于验证登录保护。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 发送请求并接受所有状态码。
        // 空响应回退为空对象。
        // 返回结果供调用方断言业务码与数据。
        // 与 POST 封装保持一致的错误处理策略。
        String raw = spec.retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }

    // 发送可带令牌的 DELETE 请求。
    // 用于删除知识库文档等资源。
    /** 辅助方法：发送可带令牌的 DELETE 请求并解析响应。 */
    private JsonNode delete(String url, String token) {
        // 构造 DELETE 请求并绑定目标地址。
        // 删除接口通过路径参数指定资源 ID。
        // 鉴权头按令牌是否存在决定。
        // 响应统一解析为树模型。
        RestClient.RequestHeadersSpec<?> spec = client.delete().uri(url);
        // 有令牌时添加 Bearer 鉴权头。
        // 匿名删除应被服务端拒绝。
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        // 发送删除请求并接受所有状态码。
        // 空响应回退为空对象，保证解析安全。
        // 返回结果供调用方断言删除业务码。
        // 删除成功后通常由后续查询确认结果。
        String raw = spec.retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }
}
