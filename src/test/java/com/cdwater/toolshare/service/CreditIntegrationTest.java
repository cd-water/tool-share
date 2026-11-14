// 信用分集成测试：覆盖初始分、加减分规则、边界裁剪、幂等与用户隔离。
package com.cdwater.toolshare.service;

// MyBatis-Plus 查询构造器：按用户或租赁单筛选信用流水。
// Lambda 形式绑定实体方法引用，避免硬编码列名。
// 用于 setScore、用户隔离等直接读库断言。
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// MyBatis-Plus 更新构造器：直接改库重置信用分与档期。
// 绕过业务校验，专门构造边界与异常场景。
// 用于重置信用分与调整租赁档期。
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
// 集成测试基类：提供完整 Spring 上下文与共享测试环境。
// 继承后可直接注入 Bean 并复用登录等公共能力。
// 同时提供随机端口与数据库隔离。
import com.cdwater.toolshare.AbstractIntegrationTest;
// 测试存储配置：用内存实现替换对象存储。
// 上传图片不落真实 S3，测试可离线运行。
// 存储实现由测试配置覆盖。
import com.cdwater.toolshare.TestStorageConfig;
// 用户实体：重置信用分时定位到种子用户。
// 信用分字段是断言的核心数据。
// 断言与清理都围绕该实体。
import com.cdwater.toolshare.module.auth.User;
// 用户 Mapper：直接改库设置测试初始分数。
// 构造 1 分、90 分、99 分等边界起点。
// 不经过接口，直接制造边界状态。
import com.cdwater.toolshare.module.auth.UserMapper;
// 信用服务：绕过 HTTP 接口直接验证事件幂等。
// 用于确认唯一约束而非仅依赖接口层校验。
// 直接调用可验证唯一约束是否生效。
import com.cdwater.toolshare.module.credit.CreditService;
// 信用事件实体：清理流水时按用户或租赁单删除。
// 每条事件记录类型、增量与关联业务 ID。
// 事件类型与增量是断言重点。
import com.cdwater.toolshare.module.credit.UserCreditEvent;
// 信用事件 Mapper：查询与清理事件流水。
// 也用于验证重复触发是否只落一条。
// 唯一约束由数据库保证，重复插入会被忽略或报错。
import com.cdwater.toolshare.module.credit.UserCreditEventMapper;
// 支付流水实体：清理租赁单时级联删除。
// 信用测试虽不直接断言支付，但需保持数据一致。
// 清理顺序需先子表后主表。
import com.cdwater.toolshare.module.rental.PaymentRecord;
// 支付流水 Mapper：按租赁单清理押金与退款记录。
// 避免残留流水影响其他结算测试。
// 与租赁单 ID 关联，便于级联清理。
import com.cdwater.toolshare.module.rental.PaymentRecordMapper;
// 租赁单实体：信用事件常由归还动作触发。
// 清理时需先删租赁再删工具。
// 信用事件通过租赁 ID 关联业务。
import com.cdwater.toolshare.module.rental.Rental;
// 费用流水实体：清理租赁单时一并删除。
// 逾期与损坏费用会触发信用扣分。
// 费用记录影响加扣分场景的前置数据。
import com.cdwater.toolshare.module.rental.RentalFee;
// 费用流水 Mapper：按租赁单删除费用记录。
// 保证信用用例不遗留结算数据。
// 删除租赁前先删除费用。
import com.cdwater.toolshare.module.rental.RentalFeeMapper;
// 租赁单 Mapper：调整档期以构造按时或逾期归还。
// 直接改库避免等待真实时间流逝。
// 档期调整是构造按时/逾期的关键手段。
import com.cdwater.toolshare.module.rental.RentalMapper;
// 评价实体：低星评价触发信用扣分。
// 清理时按租赁单删除对应评价。
// 低星评价是 LOW_REVIEW 事件的来源。
import com.cdwater.toolshare.module.review.ToolReview;
// 评价 Mapper：删除测试评价并支持低星规则断言。
// 评价与租赁单一一对应，便于定位。
// 评价与租赁一一对应。
import com.cdwater.toolshare.module.review.ToolReviewMapper;
// 工具实体：按名称前缀查询本类创建的工具。
// 工具是租赁、评价与报修的根数据。
// 工具审核状态决定能否下单。
import com.cdwater.toolshare.module.tool.Tool;
// 工具日志实体：删除工具前先清理日志。
// 日志记录工具状态变化，测试也需回收。
// 删除工具前必须先删日志。
import com.cdwater.toolshare.module.tool.ToolLog;
// 工具日志 Mapper：按工具 ID 删除日志。
// 避免共享库残留审计数据。
// 日志表可能记录工具状态流转。
import com.cdwater.toolshare.module.tool.ToolLogMapper;
// 工具 Mapper：创建、审核与删除测试工具。
// 工具必须审核通过才可被用户下单。
// 名称前缀用于批量定位测试数据。
import com.cdwater.toolshare.module.tool.ToolMapper;
// 每个用例后执行清理，防止数据串扰。
// 即使断言失败也会运行，保证可重复。
// 清理逻辑必须幂等。
import org.junit.jupiter.api.AfterEach;
// 每个用例前重置客户端与信用状态。
// 种子用户分数必须归零，避免其他测试影响。
// 重置动作保证用例可重复运行。
import org.junit.jupiter.api.BeforeEach;
// JUnit 测试注解：标记独立用例。
// 每个方法覆盖一类信用规则。
// 每个用例相互独立。
import org.junit.jupiter.api.Test;
// Spring 依赖注入：注入 Mapper、服务与端口。
// 测试上下文复用生产 Bean。
// 注入失败会让测试在启动阶段直接失败。
import org.springframework.beans.factory.annotation.Autowired;
// 启动完整应用并使用随机端口。
// 同时暴露验证码，便于双用户登录测试。
// 暴露验证码使双用户登录可自动化。
import org.springframework.boot.test.context.SpringBootTest;
// 注入随机端口，供 HTTP 客户端拼接地址。
// 避免固定端口冲突。
// 端口在上下文启动后固定。
import org.springframework.boot.test.web.server.LocalServerPort;
// 导入测试存储配置，替换对象存储实现。
// 只影响测试上下文。
// 内存存储不影响断言逻辑。
import org.springframework.context.annotation.Import;
// HTTP 方法枚举：构造 GET/POST/PATCH 请求。
// 与真实接口路径保持一致。
// 请求方法覆盖查询与写入。
import org.springframework.http.HttpMethod;
// 媒体类型常量：请求体按 JSON 发送。
// 与后端 Content-Type 约定一致。
// JSON 是前后端唯一的数据交换格式。
import org.springframework.http.MediaType;
// Spring 同步 HTTP 客户端：端到端调用真实接口。
// 验证鉴权、状态码与响应结构。
// 不依赖浏览器或前端构建产物。
import org.springframework.web.client.RestClient;
// JSON 树模型：按字段路径断言响应。
// 免去为测试定义额外 DTO。
// 断言路径与接口字段保持一致。
import tools.jackson.databind.JsonNode;
// JSON 解析器：把响应体解析为 JsonNode。
// 空响应按空对象处理。
// 解析失败会直接暴露接口契约变化。
import tools.jackson.databind.ObjectMapper;

// IO 异常：读取响应流时可能抛出。
// 捕获后转为非受检异常。
// 主要出现在读取响应体时。
import java.io.IOException;
// 输入流：读取接口返回的字节。
// try-with-resources 保证及时关闭。
// 响应体可能为空，需要容错。
import java.io.InputStream;
// 高精度金额类型：创建工具时设置押金。
// 避免浮点误差影响断言。
// 金额断言使用 compareTo 避免精度问题。
import java.math.BigDecimal;
// 字符集常量：响应体按 UTF-8 解码。
// 防止中文内容乱码。
// 中文响应内容需要正确解码。
import java.nio.charset.StandardCharsets;
// 本地时间：构造归还时刻与租赁档期。
// 固定到秒，便于与接口格式对齐。
// 所有时间比较都基于同一时区。
import java.time.LocalDateTime;
// 日期格式化器：把时间转为接口要求的字符串。
// 去掉纳秒，避免精度差异。
// 秒级精度足以覆盖业务规则。
import java.time.format.DateTimeFormatter;
// HashMap：在归还请求体中透传额外字段。
// 支持减免开关与客户端伪造金额等场景。
// 额外字段用于减免与伪造金额场景。
import java.util.HashMap;
// 集合工具：构造空图片列表与用户 ID 列表。
// 遍历工具数据时也使用 List。
// 固定列表便于构造请求体。
import java.util.List;
// Map 工具：快速拼装请求体与登录参数。
// 使用不可变 Map 减少意外修改。
// 登录参数也使用 Map 传递。
import java.util.Map;

// 断言相等：覆盖分数、状态、业务码与金额。
// 失败消息携带实际响应，便于排查。
// 断言失败信息包含完整响应体。
import static org.junit.jupiter.api.Assertions.assertEquals;
// 断言为假：用于校验原因字符串非空等场景。
// 与 assertTrue 配合表达否定条件。
// 用于校验原因字段非空。
import static org.junit.jupiter.api.Assertions.assertFalse;
// 断言为真：校验事件唯一性与提示文案。
// 失败时输出上下文数据。
// 用于校验事件唯一与错误提示。
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信用分集成测试。
 * <p>
 * 测试范围：初始分数、按时/逾期/严重损坏/低星评价的增减分、
 * 0-100 边界裁剪、事件流水与分数一致性、幂等去重与用户隔离。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储，并暴露验证码便于双用户登录。
 * 关键不变量：初始 100；按时 +2、逾期 -3、严重损坏 -10、低星（≤2 星）-5；
 * 分数始终裁剪在 0-100；每条流水与分数增量一致；
 * 同一租赁/评价重复触发只记一次；查询始终落在登录用户，不能越权读取他人信用。
 * <p>
 * 覆盖路径：初始分、四类加减分规则、边界裁剪、幂等、账本一致性与用户隔离。
 * 初始分：新用户为 100，查询时没有任何信用流水。
 * 登录要求：匿名查询返回 401；查询始终作用于当前登录用户。
 * 幂等保证：同一租赁或评价重复触发只记一次，依赖数据库唯一约束。
 * 账本一致性：所有事件增量之和等于当前分数减去初始分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.auth.expose-captcha=true")
@Import(TestStorageConfig.class)
class CreditIntegrationTest extends AbstractIntegrationTest {

    // 统一时间格式：与接口契约一致，精确到秒。
    // 固定格式避免不同用例生成的字符串不可比较。
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // 工具名称统一前缀：清理时按前缀批量定位。
    // 同时保证不同用例创建的工具互不冲突。
    private static final String PREFIX = "IT-CREDIT-";

    // 随机端口由 Spring 启动时分配。
    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    // 用户 Mapper：直接改库重置信用分。
    // 绕过接口构造 1、90、99 等初始分数。
    /** 用户 Mapper，用于重置信用分。 */
    @Autowired
    UserMapper userMapper;
    // 信用事件 Mapper：清理流水并统计事件条数。
    // 事件流水是幂等与账本断言的依据。
    /** 信用事件 Mapper，用于清理流水并校验记录。 */
    @Autowired
    UserCreditEventMapper eventMapper;
    // 工具 Mapper：创建唯一名称工具并审核通过。
    // 工具是下单与归还流程的起点。
    /** 工具 Mapper，用于创建与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 工具日志 Mapper：删除工具前先清理日志。
    // 日志记录工具状态流转。
    /** 工具日志 Mapper，清理工具前先删关联日志。 */
    @Autowired
    ToolLogMapper toolLogMapper;
    // 租赁单 Mapper：调整档期并清理租赁数据。
    // 档期直接决定按时还是逾期。
    /** 租赁单 Mapper，用于部署归还场景与断言关联。 */
    @Autowired
    RentalMapper rentalMapper;
    // 费用流水 Mapper：按租赁单清理逾期与赔偿记录。
    // 费用与信用扣分规则相关。
    /** 费用流水 Mapper，清理租赁时级联删除。 */
    @Autowired
    RentalFeeMapper rentalFeeMapper;
    // 支付流水 Mapper：按租赁单清理押金与退款记录。
    // 保持结算数据一致。
    /** 支付流水 Mapper，清理租赁时级联删除。 */
    @Autowired
    PaymentRecordMapper paymentRecordMapper;
    // 评价 Mapper：清理低星评价并校验规则。
    // 低星评价是信用扣分来源之一。
    /** 评价 Mapper，清理租赁关联评价并用于低星规则。 */
    @Autowired
    ToolReviewMapper reviewMapper;
    // 信用服务：直接调用记录方法验证数据库唯一约束。
    // 这是绕过接口层校验、验证幂等的关键依赖。
    /** 信用服务，用于直接验证数据库唯一约束的幂等性。 */
    @Autowired
    CreditService creditService;

    // HTTP 客户端在每个用例前按随机端口重建。
    // ObjectMapper 复用于解析所有响应。
    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    /**
     * 每个用例前创建 HTTP 客户端，并把种子用户 1/2 的信用分重置为 100、
     * 删除其信用流水。其他测试类也可能触发信用事件，因此必须主动归零。
     * 客户端指向随机端口，供所有用例复用。
     * 重置与清空必须幂等，保证失败重跑时环境仍然干净。
     */
    @BeforeEach
    void setUp() {
        // 创建指向随机端口的客户端。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 种子用户 1 用于被测用户，用户 2 用于越权隔离测试。
        // 各测试独立：seed 用户 1/2 的分数与流水归零（其他测试类也会触发信用事件）
        // 两个种子用户都重置，避免任一用例受到其他用例影响。
        for (long userId : List.of(1L, 2L)) {
            // 直接改库把分数设为 100，等价于新用户初始分。
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getCreditScore, 100));
            // 已存在的事件流水必须清空，否则总数与账本断言会失真。
            eventMapper.delete(new LambdaQueryWrapper<UserCreditEvent>()
                    .eq(UserCreditEvent::getUserId, userId));
        }
    }

    /**
     * 用例结束后按工具名称前缀清理租赁单及其评价、费用、支付与信用流水，
     * 再删工具日志与工具，保证信用统计不受残留数据影响。
     * 按工具前缀定位本类数据，再沿租赁单清理评价、费用、支付与信用事件。
     * 删除顺序从子表到主表，避免外键约束冲突。
     * 工具日志与工具最后删除，形成完整回收链。
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
            // 逐笔清理租赁单的关联数据。
            for (Rental rental : rentals) {
                // 删除该租赁的评价，避免影响低星规则测试。
                reviewMapper.delete(new LambdaQueryWrapper<ToolReview>()
                        .eq(ToolReview::getRentalId, rental.getId()));
                // 删除逾期或赔偿费用流水。
                rentalFeeMapper.delete(new LambdaQueryWrapper<RentalFee>()
                        .eq(RentalFee::getRentalId, rental.getId()));
                // 删除押金、退款与应收流水。
                paymentRecordMapper.delete(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getRentalId, rental.getId()));
                // 删除该租赁触发的信用事件流水。
                eventMapper.delete(new LambdaQueryWrapper<UserCreditEvent>()
                        .eq(UserCreditEvent::getRentalId, rental.getId()));
                // 最后删除租赁单本身。
                rentalMapper.deleteById(rental.getId());
            }
            // 清理工具日志。
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            // 删除工具，完成整条数据链回收。
            toolMapper.deleteById(tool.getId());
        }
    }

    /**
     * 场景：登录后查询信用分，并以匿名身份再次查询。
     * 前置条件：setUp 已将分数重置为 100 且清空流水。
     * 断言目标：分数 100、事件总数为 0、记录为空；
     * 匿名请求返回 401，信用数据只能由本人读取。
     * <p>
     * 该用例同时验证三件事：初始分数、事件账本为空、查询需要登录。
     * 前置状态由 setUp 保证：分数 100、无流水。
     * 匿名访问必须被鉴权拦截，不能返回任何信用数据。
     * 该用例是其他用例的基线，失败通常意味着初始化被污染。
     * 断言直接读取返回的 data 字段，避免依赖响应包装结构。
     */
    @Test
    void initial_credit_is_100_with_no_events_and_requires_login() {
        // 用种子用户密码登录，取得查询信用所需的令牌。
        String user = userLogin();
        // 查询信用首页数据，包含分数与事件分页。
        JsonNode data = credit(user);
        // 初始分数应为 100。
        assertEquals(100, data.get("score").asInt(), data.toString());
        // 事件总数为 0，说明没有历史信用流水。
        assertEquals(0, data.get("events").get("total").asInt(), data.toString());
        // 当前页记录也为空，与总数一致。
        assertEquals(0, data.get("events").get("records").size(), data.toString());

        // 匿名请求：不携带令牌访问信用接口。
        ApiResponse anonymous = call(HttpMethod.GET, "/api/user/credit", null, null);
        // 未登录应返回 401，信用数据只对本人开放。
        assertEquals(401, anonymous.status(), anonymous.body().toString());
    }

    /**
     * 场景：用户初始 90 分，按时完好归还一笔租赁。
     * 前置条件：租期被调整为已开始但未结束，归还判定为按时。
     * 断言目标：分数变为 92；只产生一条 ON_TIME_RETURN 流水，
     * 增量为 +2、关联租赁 ID 正确且原因非空，保证可追溯。
     * <p>
     * 按时归还的判定依据是实际归还时间不晚于约定结束时间。
     * 归还动作由管理员发起，普通用户不能自行完成验收。
     * 该用例同时验证分数变化与事件流水的可追溯字段。
     * 初始分设为 90，避免触及 100 分上限导致加分不可见。
     * 事件原因必须非空，便于后续审计与用户解释。
     */
    @Test
    void on_time_return_adds_two_and_records_event() {
        // 管理员登录，用于创建工具、取件与归还。
        String admin = adminLogin();
        // 被测用户登录，初始分随后设为 90。
        String user = userLogin();
        // 走完创建、支付、取件流程，得到可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "on-time");
        // 将用户 1 的分数压到 90，便于观察 +2。
        setScore(1L, 90);
        // 固定当前时刻并去掉纳秒，避免时间格式差异。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 把租期改为已开始但未结束，归还判定为按时。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        // 以完好状态归还；ok 会同时断言 HTTP 200 与业务码 A200。
        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        // 重新查询信用，读取最新分数与流水。
        JsonNode data = credit(user);
        // 按时归还加 2 分：90 + 2 = 92。
        assertEquals(92, data.get("score").asInt(), data.toString());
        // 取出该租赁唯一的 ON_TIME_RETURN 事件。
        JsonNode event = onlyEvent(data, "ON_TIME_RETURN");
        // 流水增量为 +2，与分数变化一致。
        assertEquals(2, event.get("delta").asInt(), event.toString());
        // 事件关联的租赁单 ID 必须正确，便于追溯。
        assertEquals(rentalId, event.get("rentalId").asLong(), event.toString());
        // 原因字段非空，保证审计信息可读。
        assertFalse(event.get("reason").asString().isBlank(), event.toString());
    }

    /**
     * 场景：用户初始 90 分，逾期归还且工具完好。
     * 前置条件：结束时间已过，实际归还晚于结束时间。
     * 断言目标：分数降至 87；产生 OVERDUE 流水且增量为 -3，
     * 不得同时记按时归还事件，避免规则叠加。
     * <p>
     * 逾期判定只看实际归还时间是否晚于约定结束时间。
     * 逾期规则与损坏规则互斥：同一归还不能既记按时又记逾期。
     * 分数从 90 降到 87，验证扣分幅度为 3。
     * 事件增量与实际分数变化必须一致。
     * 通过 countEvents 确认没有误记 ON_TIME_RETURN。
     */
    @Test
    void overdue_return_deducts_three() {
        // 管理员登录，后续负责取件与归还。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备一笔可取件的租赁单。
        long rentalId = pickedUpRental(admin, user, "overdue");
        // 初始分设为 90，便于观察逾期扣 3 分。
        setScore(1L, 90);
        // 当前时刻固定到秒。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 结束时间设在一小时前，归还必然逾期。
        setSlot(rentalId, now.minusHours(4), now.minusHours(3));

        // 完好归还，但时间已超过约定结束时间。
        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        // 查询最新信用数据。
        JsonNode data = credit(user);
        // 逾期扣 3 分：90 - 3 = 87。
        assertEquals(87, data.get("score").asInt(), data.toString());
        // 取出唯一 OVERDUE 事件。
        JsonNode event = onlyEvent(data, "OVERDUE");
        // 逾期流水增量为 -3。
        assertEquals(-3, event.get("delta").asInt(), event.toString());
        // 事件关联到本次租赁单。
        assertEquals(rentalId, event.get("rentalId").asLong(), event.toString());
        // 不得再记按时归还事件，避免规则重复生效。
        assertEquals(0, countEvents(data, "ON_TIME_RETURN"), data.toString());
    }

    /**
     * 场景：按时归还但验收为严重损坏。
     * 前置条件：按时规则与损坏规则可以同时生效。
     * 断言目标：分数 90+2-10=82；同时存在 +2 的按时流水与 -10 的严重损坏流水，
     * 证明两条规则独立记账。
     * <p>
     * 该用例验证两条规则可以叠加：按时归还 + 严重损坏。
     * 按时规则先加 2 分，损坏规则再扣 10 分。
     * 两条事件必须分别落账，不能合并为一条。
     * 最终分数 82 证明两条规则独立生效。
     * 若实现只保留一条规则，本用例会失败。
     */
    @Test
    void severe_damage_deducts_ten_alongside_return_rule() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备可归还的租赁单。
        long rentalId = pickedUpRental(admin, user, "severe");
        // 初始 90 分。
        setScore(1L, 90);
        // 当前时刻固定到秒。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 租期未结束，归还本身属于按时。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        // 按时归还，但验收结果为严重损坏。
        ok(returnRental(admin, rentalId, now, "SEVERE", Map.of()));

        // 查询信用数据。
        JsonNode data = credit(user);
        // 90 + 2（按时）- 10（严重损坏）= 82。
        assertEquals(82, data.get("score").asInt(), data.toString());
        // 按时流水增量为 +2。
        assertEquals(2, onlyEvent(data, "ON_TIME_RETURN").get("delta").asInt(),
                data.toString());
        // 严重损坏流水增量为 -10，与按时流水独立。
        assertEquals(-10, onlyEvent(data, "SEVERE_DAMAGE").get("delta").asInt(),
                data.toString());
    }

    /**
     * 场景：两笔按时归还后分别提交 2 星与 3 星评价。
     * 前置条件：只有不超过 2 星的评价触发扣分。
     * 断言目标：分数为 90+2+2-5=89；只产生一条 LOW_REVIEW 流水，
     * 关联 2 星评价的 reviewId 与租赁 ID，3 星评价不扣分。
     * <p>
     * 低星门槛为不超过 2 星，3 星及以上不扣分。
     * 两笔租赁分别提交 2 星与 3 星评价，形成对照。
     * 只有 2 星评价产生 LOW_REVIEW 流水。
     * 事件需关联评价 ID 与租赁 ID，保证可追溯。
     * 分数变化为 +2 +2 -5，验证低星只扣一次。
     */
    @Test
    void low_review_deducts_five_only_for_two_stars_or_less() {
        // 管理员登录，用于取件与归还两笔租赁。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 第一笔租赁用于提交 2 星评价。
        long first = pickedUpRental(admin, user, "low-review");
        // 第二笔租赁用于提交 3 星评价作对照。
        long second = pickedUpRental(admin, user, "normal-review");
        // 初始分设为 90。
        setScore(1L, 90);
        // 当前时刻固定到秒。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 第一笔租期调整为按时归还。
        setSlot(first, now.minusHours(3), now.plusHours(1));
        // 第二笔租期同样调整为按时归还。
        setSlot(second, now.minusHours(3), now.plusHours(1));
        // 归还第一笔，触发 +2。
        ok(returnRental(admin, first, now, "INTACT", Map.of()));
        // 归还第二笔，再触发 +2。
        ok(returnRental(admin, second, now, "INTACT", Map.of()));

        // 提交 2 星评价，应触发低星扣分。
        JsonNode review = ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", first, "rating", 2, "content", "不太满意")));
        // 记录评价 ID，稍后与流水关联字段比对。
        long reviewId = review.get("data").get("id").asLong();
        // 提交 3 星评价，不应触发扣分。
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", second, "rating", 3, "content", "一般")));

        // 查询信用数据。
        JsonNode data = credit(user);
        // 期望分数：90 + 2 + 2 - 5 = 89。
        // 90 + 2 + 2 - 5（仅 2 星评价扣分）
        // 只有 2 星评价产生 LOW_REVIEW 流水。
        assertEquals(89, data.get("score").asInt(), data.toString());
        // 取出唯一的低星评价事件。
        JsonNode event = onlyEvent(data, "LOW_REVIEW");
        // 低星扣分增量为 -5。
        assertEquals(-5, event.get("delta").asInt(), event.toString());
        // 事件关联的评价 ID 正确。
        assertEquals(reviewId, event.get("reviewId").asLong(), event.toString());
        // 事件关联的租赁单是第一笔，而非 3 星那笔。
        assertEquals(first, event.get("rentalId").asLong(), event.toString());
    }

    /**
     * 场景：1 分时触发逾期扣分；99 分时触发按时加分。
     * 前置条件：流水记录原始增量，但展示分数必须裁剪。
     * 断言目标：下界结果为 0、上界结果为 100；
     * 流水中的 delta 仍为真实 -1/+1，便于审计与重算。
     * <p>
     * 下界场景：1 分时触发逾期扣分，展示分数不得为负。
     * 上界场景：99 分时触发按时加分，展示分数不得超 100。
     * 流水记录实际生效的增量，边界处可能小于规则面值。
     * 展示分数由业务层裁剪，数据库流水仍保留真实变化。
     * 该用例同时覆盖两个方向的裁剪逻辑。
     */
    @Test
    void score_is_clamped_between_zero_and_hundred() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 当前时刻固定到秒。
        LocalDateTime now = LocalDateTime.now().withNano(0);

        // 下界场景：准备一笔可归还租赁。
        long floorRental = pickedUpRental(admin, user, "floor");
        // 把分数压到 1，逾期扣 3 后应被裁剪为 0。
        setScore(1L, 1);
        // 设置已逾期档期。
        setSlot(floorRental, now.minusHours(4), now.minusHours(3));
        // 归还触发逾期扣分。
        ok(returnRental(admin, floorRental, now, "INTACT", Map.of()));

        // 查询下界结果。
        JsonNode floored = credit(user);
        // 展示分数被裁剪为 0，不会变成负数。
        assertEquals(0, floored.get("score").asInt(), floored.toString());
        // 流水 delta 为实际生效的 -1：扣 3 分但只能扣到 0，避免负分。
        assertEquals(-1, onlyEvent(floored, "OVERDUE").get("delta").asInt(),
                floored.toString());

        // 上界场景：再准备一笔租赁。
        long capRental = pickedUpRental(admin, user, "cap");
        // 分数设为 99，按时 +2 后应封顶 100。
        setScore(1L, 99);
        // 设置按时归还档期。
        setSlot(capRental, now.minusHours(3), now.plusHours(1));
        // 归还触发加分。
        ok(returnRental(admin, capRental, now, "INTACT", Map.of()));

        // 查询上界结果。
        JsonNode capped = credit(user);
        // 展示分数封顶 100。
        assertEquals(100, capped.get("score").asInt(), capped.toString());
        // 流水 delta 为实际生效的 +1，而非 +2。
        assertEquals(1, onlyEvent(capped, "ON_TIME_RETURN").get("delta").asInt(),
                capped.toString());
    }

    /**
     * 场景：同一租赁单重复归还、同一租赁单重复评价。
     * 前置条件：首次归还与评价已成功并计分。
     * 断言目标：重复请求返回 409；分数只按首轮变化；
     * ON_TIME_RETURN 与 LOW_REVIEW 各只有一条，防止刷分或重复扣分。
     * <p>
     * 重复归还与重复评价都应被状态机或唯一约束拦截。
     * 首次操作成功计分，重复操作返回 409 且不再计分。
     * 事件条数用于确认没有产生第二条流水。
     * 分数变化只按首轮计算，防止刷分或重复扣分。
     * 该用例覆盖接口层的幂等，与直接调用服务的用例互补。
     */
    @Test
    void duplicate_return_and_review_do_not_double_count() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 准备一笔可归还租赁。
        long rentalId = pickedUpRental(admin, user, "duplicate");
        // 初始分 90，便于观察首轮 +2 与后续 -5。
        setScore(1L, 90);
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 设置按时归还档期。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));

        // 首次归还成功，应加分并落一条流水。
        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));
        // 对同一租赁单再次归还，模拟重复提交。
        ApiResponse duplicateReturn = returnRental(admin, rentalId, now, "INTACT", Map.of());
        // 重复归还返回 409 冲突，不得二次结算。
        assertEquals(409, duplicateReturn.status(), duplicateReturn.body().toString());

        // 查询归还后的信用数据。
        JsonNode afterReturn = credit(user);
        // 分数只增加一次：90 + 2 = 92。
        assertEquals(92, afterReturn.get("score").asInt(), afterReturn.toString());
        // ON_TIME_RETURN 流水只有一条。
        assertEquals(1, countEvents(afterReturn, "ON_TIME_RETURN"), afterReturn.toString());

        // 准备一条 1 星评价，继续验证重复评价。
        Map<String, Object> review = Map.of(
                "rentalId", rentalId, "rating", 1, "content", "差评");
        // 首次评价成功，触发低星扣分。
        ok(call(HttpMethod.POST, "/api/user/reviews", user, review));
        // 对同一租赁再次提交评价。
        ApiResponse duplicateReview = call(HttpMethod.POST, "/api/user/reviews", user, review);
        // 重复评价返回 409，防止重复扣分。
        assertEquals(409, duplicateReview.status(), duplicateReview.body().toString());

        // 查询评价后的信用数据。
        JsonNode afterReview = credit(user);
        // 分数只再扣一次：92 - 5 = 87。
        assertEquals(87, afterReview.get("score").asInt(), afterReview.toString());
        // LOW_REVIEW 流水只有一条。
        assertEquals(1, countEvents(afterReview, "LOW_REVIEW"), afterReview.toString());
    }

    /**
     * 场景：绕过接口直接重复调用信用事件记录方法。
     * 前置条件：服务层依赖数据库唯一键保证幂等。
     * 断言目标：两次相同事件只生效一次；分数 90+2-5=87；
     * 两类事件各一条，验证唯一约束而非仅靠接口状态校验。
     * <p>
     * 该用例绕过接口，直接重复调用信用事件记录方法。
     * 数据库唯一键应保证相同业务事件只落一条。
     * 分数只按一次归还与一次评价计算。
     * 事件条数分别验证两类规则都具备幂等性。
     * 与接口幂等用例互补，定位问题在服务层还是控制层。
     */
    @Test
    void unique_constraint_makes_repeated_events_idempotent() {
        // 只登录用户，本用例不经过 HTTP 业务接口。
        String user = userLogin();
        // 初始分设为 90。
        setScore(1L, 90);
        // 连续两次触发同一归还事件：第一次 +2，第二次应被唯一键忽略。
        // 模拟同一租赁/评价被直接重复触发，唯一键必须挡住第二次
        // 第一次记录归还事件，产生 ON_TIME_RETURN。
        creditService.recordReturn(1L, 900_000_001L, "IT-CREDIT-DUP", true, false);
        // 第二次传入相同租赁 ID，预期不会重复记账。
        creditService.recordReturn(1L, 900_000_001L, "IT-CREDIT-DUP", true, false);
        // 第一次记录低星评价事件，产生 LOW_REVIEW。
        creditService.recordReview(1L, 900_000_001L, 900_000_002L, 1);
        // 第二次传入相同评价 ID，预期不会重复扣分。
        creditService.recordReview(1L, 900_000_001L, 900_000_002L, 1);

        // 查询分数与流水，验证幂等结果。
        JsonNode data = credit(user);
        // 90 + 2 - 5 = 87，只计算一次。
        assertEquals(87, data.get("score").asInt(), data.toString());
        // ON_TIME_RETURN 仅一条。
        assertEquals(1, countEvents(data, "ON_TIME_RETURN"), data.toString());
        // LOW_REVIEW 仅一条。
        assertEquals(1, countEvents(data, "LOW_REVIEW"), data.toString());
    }

    /**
     * 场景：连续产生按时、低星、逾期、严重损坏等 6 条信用事件。
     * 前置条件：分数从 100 开始，最终应为 79。
     * 断言目标：首页分数 79、事件总数 6 且分页大小生效；
     * 最后一页仍可读取；所有流水增量之和等于“当前分数-100”，
     * 证明账本与分数严格一致。
     * <p>
     * 三笔租赁依次产生按时、低星、逾期、低星、按时、损坏共 6 条事件。
     * 初始 100 分，最终 79 分，分页查询需返回正确总数。
     * 每页 2 条时，第 1 页与第 3 页都应可读取。
     * 所有事件增量之和必须等于当前分数减去初始分。
     * 该用例验证账本与分数快照严格一致，防止统计漂移。
     */
    @Test
    void ledger_sum_matches_score_and_is_paginated() {
        // 管理员登录。
        String admin = adminLogin();
        // 被测用户登录。
        String user = userLogin();
        // 固定当前时刻，供三笔租赁共用。
        LocalDateTime now = LocalDateTime.now().withNano(0);

        // 第一笔：按时归还场景。
        long onTime = pickedUpRental(admin, user, "ledger-on-time");
        // 租期设为按时。
        setSlot(onTime, now.minusHours(3), now.plusHours(1));
        // 归还后 100 分已封顶，+2 不产生实际上涨。
        ok(returnRental(admin, onTime, now, "INTACT", Map.of())); // 100 +0（封顶）
        // 提交 1 星评价，扣 5 分至 95。
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", onTime, "rating", 1))); // -5 => 95

        // 第二笔：逾期归还场景。
        long overdue = pickedUpRental(admin, user, "ledger-overdue");
        // 结束时间设为过去，触发逾期。
        setSlot(overdue, now.minusHours(4), now.minusHours(3));
        // 逾期扣 3 分，从 95 到 92。
        ok(returnRental(admin, overdue, now, "INTACT", Map.of())); // -3 => 92
        // 提交 2 星评价，再扣 5 分至 87。
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", overdue, "rating", 2))); // -5 => 87

        // 第三笔：严重损坏场景。
        long severe = pickedUpRental(admin, user, "ledger-severe");
        // 按时归还档期。
        setSlot(severe, now.minusHours(3), now.plusHours(1));
        // 按时 +2 同时严重损坏 -10，净变化 -8，从 87 到 79。
        ok(returnRental(admin, severe, now, "SEVERE", Map.of())); // +2 -10 => 79

        // 查询第一页：每页 2 条，读取分数与分页信息。
        JsonNode firstPage = ok(call(HttpMethod.GET,
                // 请求第 1 页、每页 2 条。
                "/api/user/credit?page=1&size=2", user, null)).get("data");
        // 最终分数为 79。
        assertEquals(79, firstPage.get("score").asInt(), firstPage.toString());
        // 事件总数为 6 条。
        assertEquals(6, firstPage.get("events").get("total").asInt(), firstPage.toString());
        // 当前页只返回 2 条，分页大小生效。
        assertEquals(2, firstPage.get("events").get("records").size(), firstPage.toString());

        // 查询最后一页（第 3 页），验证剩余事件可读取。
        JsonNode lastPage = ok(call(HttpMethod.GET,
                // 请求第 3 页、每页 2 条。
                "/api/user/credit?page=3&size=2", user, null)).get("data");
        // 最后一页仍有 2 条记录。
        assertEquals(2, lastPage.get("events").get("records").size(), lastPage.toString());

        // 拉取全部事件用于账本求和。
        JsonNode all = ok(call(HttpMethod.GET,
                // 请求第 1 页、每页 100 条，一次取回全部事件。
                "/api/user/credit?page=1&size=100", user, null)).get("data");
        // 累加所有事件的实际增量。
        int sum = 0;
        // 遍历事件记录，逐条累加 delta。
        for (JsonNode event : all.get("events").get("records")) {
            // delta 是实际生效的增减分，已包含边界裁剪。
            sum += event.get("delta").asInt();
        }
        // 账本一致性：分数 - 初始 100 应等于增量之和。
        assertEquals(all.get("score").asInt() - 100, sum, all.toString());
    }

    /**
     * 场景：用户 1 产生信用事件，用户 2 查询自己的信用，
     * 用户 1 再通过查询参数尝试读取用户 2 的数据。
     * 前置条件：查询接口不接受 userId 参数。
     * 断言目标：用户 2 仍为 100 分且无流水；用户 1 即使传 userId=2
     * 也只返回自己的 92 分，防止水平越权。
     * <p>
     * 用户 1 产生信用事件后，用户 2 的数据必须保持独立。
     * 查询接口不接受 userId 参数，始终以登录身份为准。
     * 即使显式传入 userId=2，也只能读取自己的数据。
     * 该用例防止水平越权与信用数据泄露。
     * 用户 2 初始分与空流水是隔离性的直接证据。
     */
    @Test
    void credit_is_scoped_to_the_logged_in_user() {
        // 管理员登录。
        String admin = adminLogin();
        // 用户 1 登录，用于产生信用事件。
        String user = userLogin();
        // 准备一笔可归还租赁。
        long rentalId = pickedUpRental(admin, user, "scoped");
        // 用户 1 初始分设为 90。
        setScore(1L, 90);
        // 固定当前时刻。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 设置按时归还档期。
        setSlot(rentalId, now.minusHours(3), now.plusHours(1));
        // 归还后用户 1 变为 92 分。
        ok(returnRental(admin, rentalId, now, "INTACT", Map.of()));

        // 用验证码登录用户 2，验证用户间隔离。
        String other = loginByCode("13800000002");
        // 查询用户 2 的信用数据。
        JsonNode otherCredit = credit(other);
        // 用户 2 仍为初始 100 分。
        assertEquals(100, otherCredit.get("score").asInt(), otherCredit.toString());
        // 用户 2 没有任何信用流水。
        assertEquals(0, otherCredit.get("events").get("total").asInt(), otherCredit.toString());

        // 无法通过参数读取他人分数：查询始终落在登录用户
        // 用户 1 尝试通过查询参数指定 userId=2。
        JsonNode spoofed = ok(call(HttpMethod.GET,
                "/api/user/credit?userId=2", user, null)).get("data");
        // 接口忽略该参数，仍返回登录用户自己的 92 分。
        assertEquals(92, spoofed.get("score").asInt(), spoofed.toString());
        // 只返回用户 1 自己的 1 条流水，未泄露用户 2 数据。
        assertEquals(1, spoofed.get("events").get("total").asInt(), spoofed.toString());
    }

    // 固定第一页、每页 10 条，覆盖首页查询路径。
    /** 辅助方法：查询指定令牌的信用分首页数据。 */
    private JsonNode credit(String token) {
        // 调用信用接口并直接取出 data 字段。
        return ok(call(HttpMethod.GET, "/api/user/credit?page=1&size=10", token, null))
                .get("data");
    }

    /**
     * 辅助方法：查找指定类型的唯一信用事件。
     * 若出现多条或缺失都直接断言失败，用于验证幂等性。
     * 通过事件类型精确匹配，避免误把其他规则当成目标事件。
     * 返回首个匹配项，便于调用方继续断言字段。
     */
    private JsonNode onlyEvent(JsonNode data, String type) {
        // 用 null 表示尚未找到，首次命中后即记录。
        JsonNode found = null;
        // 遍历当前页的所有信用事件。
        for (JsonNode event : data.get("events").get("records")) {
            // 只关注指定事件类型。
            if (type.equals(event.get("eventType").asString())) {
                // 第二次命中说明重复记账，立即断言失败。
                assertTrue(found == null, type + " 应只有一条：" + data);
                // 保存首个匹配事件，供调用方读取字段。
                found = event;
            }
        }
        // 遍历结束仍未找到，说明事件缺失。
        assertTrue(found != null, "缺少 " + type + " 事件：" + data);
        // 返回唯一匹配的事件。
        return found;
    }

    // 统计条数用于验证幂等与规则是否重复触发。
    /** 辅助方法：统计指定类型的信用事件条数。 */
    private int countEvents(JsonNode data, String type) {
        // 计数器从 0 开始。
        int count = 0;
        // 遍历当前页事件记录。
        for (JsonNode event : data.get("events").get("records")) {
            // 只统计指定类型。
            if (type.equals(event.get("eventType").asString())) {
                // 匹配一次加一。
                count++;
            }
        }
        // 返回该类型的条数。
        return count;
    }

    // 直接更新用户表，绕过业务校验。
    /** 辅助方法：直接改库设置用户信用分，构造边界测试起点。 */
    private void setScore(long userId, int score) {
        // 更新指定用户的信用分。
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                // 按用户 ID 定位。
                .eq(User::getId, userId)
                // 设置测试起点分数。
                .set(User::getCreditScore, score));
    }

    /**
     * 辅助方法：创建工具、下单、支付并取件，返回可直接归还的租赁单 ID。
     * 把信用测试的前置流程集中封装，避免重复拼装请求。
     * 支付与取件都断言成功，否则后续归还用例不成立。
     * 返回的租赁单处于 PICKED_UP 状态，可直接调用归还接口。
     */
    private long pickedUpRental(String admin, String user, String suffix) {
        // 先创建并审核一个测试工具。
        long toolId = createApprovedTool(admin, suffix);
        // 以两天后的档期创建租赁单。
        long rentalId = createRental(user, toolId);
        // 支付押金，使订单进入待取件状态。
        assertEquals(200, call(HttpMethod.POST, "/api/user/rentals/" + rentalId + "/pay",
                user, Map.of()).status());
        // 管理员取件，订单变为 PICKED_UP 后可归还。
        assertEquals(200, call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/pickup",
                admin, Map.of()).status());
        // 返回租赁单 ID，供用例设置档期并归还。
        return rentalId;
    }

    /**
     * 辅助方法：创建唯一名称工具并审核通过，返回工具 ID。
     // 名称带前缀与后缀，既唯一又便于清理。
     */
    private long createApprovedTool(String admin, String suffix) {
        // 调用管理端创建工具接口，提交完整必填字段。
        JsonNode created = ok(call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                // 名称使用统一前缀与用例后缀，保证唯一。
                "name", PREFIX + suffix,
                // 型号仅用于展示，不参与审核逻辑。
                "model", "信用测试型号",
                // 分类固定为维修类，便于清理与识别。
                "category", "WX",
                // 损坏等级为新，符合上架要求。
                "damageLevel", "NEW",
                // 押金金额影响结算测试，本类仅作占位。
                "deposit", new BigDecimal("100"),
                // 存放位置为测试柜。
                "storageArea", "测试柜",
                // 使用说明为最小 HTML 片段。
                "usageGuide", "<p>说明</p>",
                // 不附带图片，避免依赖对象存储内容。
                "imageUrls", List.of())));
        // 从响应中取出新工具 ID。
        long id = created.get("data").get("id").asLong();
        // 审核通过，使工具进入可预约状态。
        assertEquals(200, call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED")).status());
        // 返回工具 ID。
        return id;
    }

    // 下单时间设为两天后，避开过去时间校验。
    /** 辅助方法：以两天后的档期创建租赁单并返回 ID。 */
    private long createRental(String userToken, long toolId) {
        // 调用用户端下单接口。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/rentals", userToken, Map.of(
                // 指定要预约的工具。
                "toolId", toolId,
                // 开始时间为两天后。
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                // 结束时间比开始晚一天。
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                // 取件人姓名用于订单展示。
                "pickupPerson", "信用测试居民")));
        // 返回新租赁单 ID。
        return created.get("data").get("id").asLong();
    }

    // 直接改库避免等待真实时间流逝。
    /** 辅助方法：直接改库调整租赁起止时间，构造按时或逾期场景。 */
    private void setSlot(long rentalId, LocalDateTime start, LocalDateTime end) {
        // 更新指定租赁单的时间字段。
        rentalMapper.update(null, new LambdaUpdateWrapper<Rental>()
                // 按租赁单 ID 定位。
                .eq(Rental::getId, rentalId)
                // 设置新的开始时间。
                .set(Rental::getStartTime, start)
                // 设置新的结束时间，决定按时或逾期。
                .set(Rental::getEndTime, end));
    }

    /**
     * 辅助方法：组装归还请求体并调用管理端归还接口。
     * 额外字段透传，便于扩展减免等场景。
     // 额外字段透传，支持减免开关与客户端伪造金额。
     */
    private ApiResponse returnRental(String admin, long rentalId, LocalDateTime actualReturnTime,
                                     String damageResult, Map<String, Object> extra) {
        // 复制额外字段，避免修改调用方传入的 Map。
        Map<String, Object> body = new HashMap<>(extra);
        // 写入实际归还时间，格式与接口一致。
        body.put("actualReturnTime", actualReturnTime.format(FORMAT));
        // 写入验收结果：INTACT 或 SEVERE。
        body.put("damageResult", damageResult);
        // 调用管理端归还接口并返回状态码与响应体。
        return call(HttpMethod.POST, "/api/admin/rentals/" + rentalId + "/return", admin, body);
    }

    // 同时校验 HTTP 状态与业务码，避免只看一层。
    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    private JsonNode ok(ApiResponse response) {
        // 先断言 HTTP 状态为 200。
        assertEquals(200, response.status(), response.body().toString());
        // 再断言业务码为 A200。
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        // 返回响应体，便于链式读取 data。
        return response.body();
    }

    // 使用种子用户手机号与固定密码登录。
    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        // 登录接口返回的 tokenValue 放入请求头。
        return login("/api/user/login/by-pwd",
                Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /**
     * 辅助方法：发送验证码并用验证码登录，返回令牌。
     * 测试配置暴露验证码，可直接从响应中取得。
     * 发送验证码接口在测试配置下直接回传验证码，便于自动化。
     * 验证码登录用于创建第二个用户，验证信用数据隔离。
     */
    private String loginByCode(String phone) {
        // 先请求验证码；测试配置会直接在响应中返回验证码。
        String code = ok(call(HttpMethod.POST, "/api/user/send-code", null,
                Map.of("phone", phone))).get("data").asString();
        // 用手机号与验证码登录并取出令牌。
        return login("/api/user/login/by-code", Map.of("phone", phone, "code", code));
    }

    // 使用种子管理员账号密码登录。
    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        // 管理员令牌用于创建工具、取件与归还。
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    // 登录成功必须返回 tokenValue，否则后续请求无法鉴权。
    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    private String login(String url, Map<String, Object> body) {
        // 调用登录接口，令牌为空表示匿名请求。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        // 登录必须成功，否则后续请求会因缺少令牌误导。
        assertEquals(200, response.status(), response.body().toString());
        // 返回 data.tokenValue。
        return response.body().get("data").get("tokenValue").asString();
    }

    // 统一封装请求头、JSON 序列化与响应解析。
    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 构造请求方法并指定接口路径。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌时附加认证头。
        if (token != null) {
            // 使用 Bearer 方案，与后端鉴权一致。
            spec.header("Authorization", "Bearer " + token);
        }
        // 有请求体时设置 JSON 内容类型。
        if (body != null) {
            // 序列化请求体并发送。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 同步执行请求并解析响应。
        return spec.exchange((request, response) -> {
            // try-with-resources 确保响应流关闭。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取全部响应字节。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 同时保留 HTTP 状态码与响应体。
                return new ApiResponse(response.getStatusCode().value(),
                        // 空响应体按空对象处理。
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 读取或解析失败时转为非受检异常。
            } catch (IOException e) {
                // 保留原始异常原因，便于排查。
                throw new IllegalStateException(e);
            }
        // 返回状态码与 JSON 响应。
        });
    }
}
