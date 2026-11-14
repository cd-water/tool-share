package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolReview;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
import com.cdwater.toolshare.module.tool.Tool;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具评价集成测试。
 * <p>
 * 测试范围：评价创建与校验、一单一评、归属与归还状态约束、分页查询、
 * 工具详情与列表的评分摘要，以及按评分排序；管理端评价查询。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子工具/用户。
 * 关键不变量：只有本人且已归还的租赁单可评价，每单最多一条；
 * 评分 1-5、内容与图片数量有上限；评分摘要随评价实时汇总，
 * 未评价工具也必须出现在按评分排序的结果中。
 * 覆盖接口：POST/GET /api/user/reviews（创建评价与我的评价）、
 * GET /api/user/tools/{id}/reviews（工具公开评价）、GET /api/user/tools
 * 与 /api/user/tools/{id}（评分摘要）、GET /api/admin/reviews（管理端审计）。
 * 所有请求都经真实 HTTP 与安全过滤器，不复用服务层内部调用。
 * 测试数据策略：评价都挂在测试自建的租赁单上，用例结束按 ID 清理；
 * 排序与批量汇总场景使用独立假租赁 ID 直插评价，避免依赖接口状态机。
 * 并发或重复执行时依靠自增序号保持唯一键不冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ReviewIntegrationTest extends AbstractIntegrationTest {

    // 自增序号同时用于租赁单号、测试手机号与直插评价的假租赁 ID，
    // 保证并发或重复执行时不会触发唯一约束。
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    /** 租赁单 Mapper，用于构造评价前置数据并清理。 */
    @Autowired
    RentalMapper rentalMapper;
    /** 工具 Mapper，用于读取种子工具名称与 ID。 */
    @Autowired
    ToolMapper toolMapper;
    /** 评价 Mapper，用于直接插入聚合数据并清理评价。 */
    @Autowired
    ToolReviewMapper reviewMapper;

    // RestClient 在 setUp 中按随机端口重建；ObjectMapper 用于把响应
    // 原文解析成 JsonNode，业务码与数据字段都从这里读取。
    RestClient client;
    // 共享的 JSON 解析器，只读取响应、不参与序列化请求体。
    final ObjectMapper om = new ObjectMapper();
    /** 本用例创建的租赁单 ID，用例结束后统一清理。 */
    final List<Long> rentalIds = new ArrayList<>();

    // 统一的 HTTP 结果载体：同时保留原始状态码与解析后的 JSON，
    // 便于区分传输层错误、认证错误与业务码错误。
    record ApiResponse(int status, JsonNode body) {
    }

    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // Spring 启动时分配随机端口，客户端必须指向该端口才能命中被测应用。
        // 每个用例重建客户端，避免连接状态在用例之间泄漏。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 用例结束后删除本类插入的评价与租赁单。
     * 先删评价再删租赁，避免外键或业务约束残留数据。
     */
    @AfterEach
    void cleanUp() {
        // 只有本用例登记过租赁单时才执行清理，避免空 IN 条件导致全表删除。
        // 清理顺序与创建顺序相反：先删依赖租赁的评价，再删租赁单本身。
        if (!rentalIds.isEmpty()) {
            // 按登记的租赁 ID 批量删除评价，覆盖接口创建与直插两种来源。
            // 如果先删租赁单，评价会变成悬挂数据，后续用例的统计被污染。
            reviewMapper.delete(new LambdaQueryWrapper<ToolReview>()
                    .in(ToolReview::getRentalId, rentalIds));
            // 逐条删除租赁单：数量很少，逐条删除能获得更明确的失败信息。
            // 删除完成后清空登记表，防止 AfterEach 重复执行时误删他人数据。
            for (Long id : rentalIds) {
                rentalMapper.deleteById(id);
            }
            rentalIds.clear();
        }
    }

    /**
     * 场景：对已归还租赁单提交 5 星评价，内容包含首尾空白与 script 标签。
     * 前置条件：租赁单归属当前用户且状态为 RETURNED，工具使用种子数据。
     * 断言目标：评价落库并回填工具名/用户昵称；
     * 内容被去空白与消毒；我的评价、工具评价、工具详情和工具列表
     * 四处评分摘要都同步为 5 分、1 条。
     * 契约补充：创建接口只接受 rentalId/rating/content/images 四个业务字段，
     * 工具与用户信息由服务端根据租赁单推导，避免客户端伪造归属。
     * 评分摘要的四处出口必须同源：我的评价、工具评价、工具详情、工具列表。
     * 四处读数若不一致，说明聚合查询存在分叉或缓存未刷新。
     */
    @Test
    void returned_rental_review_is_created_and_reflected_in_summaries() {
        // 种子用户登录后返回 Sa-Token 的 tokenValue，后续请求放入 Bearer 头。
        // 使用真实登录而非伪造令牌，顺带覆盖认证链路。
        String user = userLogin();
        // 种子工具 ID 固定为 1（手电钻），名称断言依赖该种子数据。
        long toolId = 1L;
        // 直接读取工具行，取出 ID、编号、名称等下单快照字段。
        // 工具行同时提供下单所需的 code/name 快照，租赁单据此落库。
        Tool tool = toolMapper.selectById(toolId);
        // 构造一张归属用户 1、状态 RETURNED 的租赁单：
        // 只有“本人 + 已归还”同时成立才允许评价。
        // 状态机与评价校验共用同一张表，直接落库可跳过租赁流程。
        long rentalId = insertRental(1L, tool, "RETURNED");

        // 提交 5 星评价，内容特意带首尾空白与 script 标签，
        // 用于验证服务端的去空白与 HTML 消毒行为。
        // 使用 ok() 统一校验 200/A200，避免每个断言重复写状态码。
        JsonNode created = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", rentalId,
                "rating", 5,
                "content", "  很好用<script>alert(1)</script>  ",
                "images", List.of("https://example.com/a.jpg", "https://example.com/b.jpg"))));

        // 响应 data 即新建评价，先校验服务端回填的关联与展示字段。
        // 字段校验顺序与前端展示顺序一致，失败时定位直观。
        JsonNode review = created.get("data");
        // rentalId 必须等于请求值，确保评价挂到正确的租赁单上。
        // 这是“一单一评”与归属校验的锚点，错挂会导致统计串单。
        // 若 rentalId 不符，后续所有汇总都会落到别的租赁单上。
        assertEquals(rentalId, review.get("rentalId").asLong(), review.toString());
        // toolId 由租赁单推导，客户端不传，服务端必须回填正确。
        // 工具 ID 由服务端从租赁单反查，客户端无法越权指定。
        assertEquals(toolId, review.get("toolId").asLong(), review.toString());
        // 工具名是落库时的快照，后续工具改名不应影响历史评价展示。
        // 名称快照使历史评价在工具改名后仍显示当时的名称。
        assertEquals("手电钻", review.get("toolName").asString(), review.toString());
        // 评分原样保存；内容去空白并剔除 script 标签后只留“很好用”。
        // 评分是排序与均值的唯一输入，必须与提交值完全一致。
        assertEquals(5, review.get("rating").asInt(), review.toString());
        // 脚本标签必须被过滤，而不是仅在前端转义，防止存储型 XSS。
        // 只保留纯文本“很好用”，说明消毒发生在落库之前。
        assertEquals("很好用", review.get("content").asString(), review.toString());
        // 图片列表按请求数量保存，顺序保持客户端提交顺序。
        // 图片数量与提交一致，服务端未去重也未截断。
        assertEquals(2, review.get("images").size(), review.toString());
        // 用户昵称由服务端按 userId 回填，前端无需二次查询用户表。
        // 昵称快照避免评价列表逐条关联用户表。
        assertEquals("user_mjafo8", review.get("userNickname").asString(), review.toString());

        // 我的评价接口按当前登录用户过滤，应只看到刚创建的这一条。
        // page/size 显式传入，避免依赖服务端默认分页。
        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        // total 是过滤后的总数而非当前页条数，必须为 1。
        // 若返回多条，说明用户维度过滤缺失或清理不彻底。
        assertEquals(1, mine.get("data").get("total").asInt(), mine.toString());
        // 首条记录的 rentalId 与创建结果一致，验证列表复用了同一份数据。
        // records 默认按创建时间倒序，这里只取第一条即可。
        assertEquals(rentalId,
                mine.get("data").get("records").get(0).get("rentalId").asLong(), mine.toString());

        // 工具公开评价不按用户过滤，任何登录用户都能看到该工具的评价。
        // 该接口只要求登录，不要求评价者本人。
        JsonNode toolReviews = ok(call(HttpMethod.GET,
                "/api/user/tools/" + toolId + "/reviews?page=1&size=10", user, null));
        // 公开列表总数为 1，说明评价已挂到工具维度而非仅个人维度。
        // 公开列表的总数是工具维度计数，与个人列表相互独立。
        assertEquals(1, toolReviews.get("data").get("total").asInt(), toolReviews.toString());
        // 公开列表中的内容同样已消毒，证明清洗发生在入库前。
        // 内容透传证明公开查询没有做额外的展示层改写。
        assertEquals("很好用",
                toolReviews.get("data").get("records").get(0).get("content").asString(),
                toolReviews.toString());

        // 工具详情接口实时聚合评分，验证详情与评价表保持一致。
        // 详情接口返回工具主体与摘要，评价表只参与聚合。
        JsonNode detail = ok(call(HttpMethod.GET, "/api/user/tools/" + toolId, user, null));
        // 用 BigDecimal.compareTo 比较 5.0，规避浮点字面量的精度陷阱。
        // 均值为 0 表示没有评价，为 5.0 表示唯一一条五星评价。
        assertEquals(0, detail.get("data").get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("5.0")), detail.toString());
        // 评价计数必须同步更新，避免只算均值而漏掉评分条数。
        // 计数与均值来自同一聚合结果，不能出现一真一假。
        assertEquals(1, detail.get("data").get("reviewCount").asInt(), detail.toString());

        // 工具列表批量返回摘要，验证列表场景与详情场景口径一致。
        // 列表与详情使用不同 SQL，但必须共享同一聚合口径。
        JsonNode list = ok(call(HttpMethod.GET, "/api/user/tools?size=100", user, null));
        // 在分页记录中按 ID 线性定位，不对返回顺序作任何假设。
        // size=100 覆盖种子工具总量，避免分页截断目标工具。
        JsonNode listed = findById(list.get("data").get("records"), toolId);
        // 工具必须出现在列表中，否则后续摘要断言失去意义。
        // 找不到工具说明列表过滤条件错误或摘要字段缺失。
        assertTrue(listed != null, "工具列表应包含被评价工具");
        // 列表摘要与详情摘要应完全一致，证明共用同一聚合实现。
        // 列表摘要若与详情不同，前端会出现两处评分不一致。
        assertEquals(0, listed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("5.0")), listed.toString());
        // 计数在列表侧也必须为 1，防止批量化时漏装评分条数。
        // 列表侧的计数同样按工具统计，而非按当前页统计。
        assertEquals(1, listed.get("reviewCount").asInt(), listed.toString());
    }

    /**
     * 场景：同一租赁单连续提交两次评价。
     * 前置条件：首次提交成功。
     * 断言目标：第二次返回 409/C409，评价总数仍为 1，
     * 落实“一单一评”约束。
     * 契约补充：重复评价由服务层显式查重并返回 C409，
     * 数据库唯一索引仅作为并发兜底，两者都失效才会产生脏数据。
     * 清理会同时删除评价与租赁单，保证下一个用例看到干净的统计。
     * 失败请求不消耗租赁单，因此同一张单可以连续尝试。
     */
    @Test
    void duplicate_review_is_rejected() {
        // 登录并准备一张已归还租赁单，作为两次提交的共同目标。
        // 令牌只在本用例内使用，不跨用例缓存。
        String user = userLogin();
        // 读取种子工具行，后续插入租赁单需要工具快照字段。
        // 工具行只用于生成租赁单快照，不参与评价断言。
        Tool tool = toolMapper.selectById(1L);
        // 同一张租赁单连续评价两次，才能触发“一单一评”约束。
        // 同一张单是触发一单一评的必要条件。
        long rentalId = insertRental(1L, tool, "RETURNED");
        // 最小合法请求体：只带租赁单、评分与内容，验证可选字段可省略。
        // content 非空，确保请求先通过基础校验再触发查重。
        Map<String, Object> body = Map.of("rentalId", rentalId, "rating", 4, "content", "第一次评价");

        // 首次提交必须成功，否则第二次的冲突断言没有对照意义。
        // 首次成功会返回新评价，但本用例只关心后续冲突。
        ok(call(HttpMethod.POST, "/api/user/reviews", user, body));
        // 使用完全相同的请求体再次提交，模拟用户重复点击或重放请求。
        // 第二次请求与第一次完全相同，仅顺序不同。
        ApiResponse duplicate = call(HttpMethod.POST, "/api/user/reviews", user, body);
        // 冲突应同时体现在 HTTP 409 与业务码 C409，两层都要断言。
        // HTTP 409 让通用 HTTP 客户端也能识别冲突。
        assertEquals(409, duplicate.status(), duplicate.body().toString());
        // 业务码让前端能区分“重复评价”与其他 409 冲突。
        // C409 让前端能把“已评价”与“订单状态冲突”分开提示。
        assertEquals("C409", duplicate.body().get("code").asString(), duplicate.body().toString());

        // 再次查询我的评价，确认失败请求没有留下第二条记录。
        // 校验层面拦截与数据库唯一约束必须至少有一层兜底。
        // 用列表总数作为最终事实来源，避免只看冲突响应。
        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        // 总数仍为 1，证明查重拦截发生在插入之前。
        assertEquals(1, mine.get("data").get("total").asInt(), mine.toString());
    }

    /**
     * 场景：分别评价他人租赁单、未归还租赁单与不存在的租赁单。
     * 前置条件：当前用户 ID 为 1，他人租赁单属于用户 2。
     * 断言目标：他人单返回 403、未归还单返回 409、不存在单返回 404，
     * 防止越权评价与提前评价。
     * 契约补充：错误码语义固定为 403=归属不符、409=状态不符、404=资源缺失；
     * 服务端先查资源再判归属，因此他人订单返回 403 而非 404。
     * 这三种分支覆盖了评价入口的全部前置校验。
     * 顺序上先校验归属再校验状态，避免通过状态差异探测他人订单。
     */
    @Test
    void only_owner_and_returned_rental_can_be_reviewed() {
        // 当前登录用户 ID 为 1；下面分别构造他人、未归还与不存在的租赁单。
        // 登录用户固定为种子用户 1，归属断言以此为准。
        String user = userLogin();
        // 读取工具快照字段，三张租赁单共用同一工具以隔离变量。
        // 三张租赁单共用同一工具，变量只保留归属与状态。
        Tool tool = toolMapper.selectById(1L);

        // 归属用户 2 的已归还租赁单：状态合法但归属不符。
        // userId=2 与当前登录用户不一致，触发归属校验。
        long foreignRental = insertRental(2L, tool, "RETURNED");
        // 越权评价返回 403/C403，而不是 404，避免掩盖资源存在性。
        // 请求体合法，确保失败原因只能是归属而非参数。
        ApiResponse foreign = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", foreignRental, "rating", 5));
        // 同时校验 HTTP 状态与业务码，防止两层语义漂移。
        // 403 表示已认证但无权操作该资源。
        assertEquals(403, foreign.status(), foreign.body().toString());
        // C403 让前端明确提示“无权评价他人订单”，而非笼统失败。
        // 不能返回 404，否则会掩盖订单真实存在的事实。
        assertEquals("C403", foreign.body().get("code").asString(), foreign.body().toString());

        // 归属正确但状态仍为 PICKED_UP，表示工具尚未归还。
        // 归属正确但未归还，用状态模拟“工具还在用户手里”。
        long pickedUp = insertRental(1L, tool, "PICKED_UP");
        // 未归还订单不允许评价，防止用户提前影响工具信誉。
        // 请求体同样合法，排除参数校验的干扰。
        ApiResponse notReturned = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", pickedUp, "rating", 5));
        // 未归还属于状态冲突，返回 409 而不是 422。
        // 409 与“重复评价”共用冲突语义，但业务码仍为 C409。
        assertEquals(409, notReturned.status(), notReturned.body().toString());
        // 业务码 C409 与 HTTP 409 对应，便于前端统一处理冲突。
        // 前端可按上下文区分“未归还”与“已评价”。
        assertEquals("C409", notReturned.body().get("code").asString(),
                notReturned.body().toString());

        // 完全不存在的租赁 ID：既非本人也不存在，应走资源缺失分支。
        // 请求体合法，仅租赁 ID 不存在。
        // 不存在的资源返回 404，与越权 403 明确区分。
        ApiResponse missing = call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", 999999999L, "rating", 5));
        // 404 与 403 分开，便于前端提示“订单不存在”。
        assertEquals(404, missing.status(), missing.body().toString());
    }

    /**
     * 场景：提交评分为 0、6、超长内容与超过 3 张图片的评价。
     * 前置条件：评分区间 1-5、内容上限 500 字、图片上限 3 张。
     * 断言目标：全部返回 422/C400，且没有产生任何评价记录，
     * 保证脏数据不会进入评分摘要。
     * 契约补充：校验在事务写入前完成，失败请求不占用租赁单，
     * 因此同一张租赁单可以连续承载多次非法尝试。
     * 422 表示语义校验失败，传输层 400 不与业务校验混用。
     * 内容按字符数而非字节数计算，中文与英文使用同一上限。
     */
    @Test
    void rating_content_and_images_are_validated() {
        // 先准备一张合法租赁单，使失败只可能来自参数而非前置条件。
        // 登录用户与工具都使用种子数据，减少环境差异。
        String user = userLogin();
        // 读取种子工具快照，保证租赁单本身完全合法。
        // 工具行确保租赁单能通过外键与快照字段校验。
        Tool tool = toolMapper.selectById(1L);
        // 同一张租赁单可反复用于失败请求：校验失败不会消耗它。
        // 失败请求不会改变租赁单状态，因此可以反复使用。
        long rentalId = insertRental(1L, tool, "RETURNED");

        // 评分下界 0 非法，期望 422/C400。
        // 0 低于下界，属于可识别的业务参数错误。
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 0)));
        // 评分上界 6 非法，验证允许区间是闭区间 [1,5]。
        // 6 高于上界，验证实现没有把区间写成 [1,6]。
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 6)));
        // 内容 501 字超过 500 字上限，必须拒绝而不是静默截断。
        // 501 字刚好越界，排除“按字节计算”导致的误判。
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 5, "content", "好".repeat(501))));
        // 4 张图片超过 3 张上限，验证图片数量限制同样生效。
        // 4 张图刚好越界，验证上限是 3 而非 4。
        // 图片校验在业务写入之前完成，避免先入库再回滚。
        assertUnprocessable(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", rentalId, "rating", 5, "images", List.of(
                        "https://example.com/1.jpg", "https://example.com/2.jpg",
                        "https://example.com/3.jpg", "https://example.com/4.jpg"))));

        // 四次非法请求后仍应为零记录，证明校验失败不产生半成品数据。
        // 失败请求不应产生任何评价记录。
        JsonNode mine = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=10", user, null));
        // 总数为 0 是“未写入”的直接证据，比状态码更可靠。
        assertEquals(0, mine.get("data").get("total").asInt(), mine.toString());
    }

    /**
     * 场景：提交评分 1、内容恰好 500 字、图片恰好 3 张，以及评分 5。
     * 前置条件：边界值应被允许。
     * 断言目标：两次评价均创建成功，边界值不被误拒。
     * 契约补充：边界值采用“允许等于上限”的闭区间语义，
     * 500 字与 3 张图恰好等于上限时合法，501 字与 4 张图才拒绝。
     * 两张租赁单确保两次成功创建互不触发一单一评。
     * 该用例与拒绝用例成对出现，防止实现把边界值一律拒绝。
     */
    @Test
    void rating_and_image_boundaries_are_accepted() {
        // 两张合法租赁单分别承载下界与上界评价，互不干扰。
        // 登录用户与工具复用种子数据，聚焦边界值本身。
        String user = userLogin();
        // 读取工具快照，保证两张租赁单指向同一工具。
        // 两张租赁单共用同一工具，差异只在评价内容。
        Tool tool = toolMapper.selectById(1L);
        // 第一张用于评分 1、内容 500 字、图片 3 张的组合。
        // 第一张承载“最低分 + 最长内容 + 最多图片”。
        long first = insertRental(1L, tool, "RETURNED");
        // 第二张用于评分 5 的短内容，验证上界不被误判为非法。
        // 第二张承载“最高分 + 最短内容”。
        long second = insertRental(1L, tool, "RETURNED");

        // 三个边界值同时命中：最低分、最长内容、最多图片。
        // 请求体同时触碰三个上限，验证它们可以共存。
        JsonNode low = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", first,
                "rating", 1,
                "content", "好".repeat(500),
                "images", List.of("https://example.com/1.jpg",
                        "https://example.com/2.jpg", "https://example.com/3.jpg"))));
        // 边界值必须原样保存，不能被裁剪成 499 字或 2 张图。
        // 评分 1 是合法下界，必须原样保存。
        assertEquals(1, low.get("data").get("rating").asInt(), low.toString());
        // 500 字恰好等于上限，不能被裁剪。
        assertEquals(500, low.get("data").get("content").asString().length(), low.toString());
        // 3 张图恰好等于上限，不能被截断。
        assertEquals(3, low.get("data").get("images").size(), low.toString());

        // 另一极端：最高分与短内容，验证上界评价可正常创建。
        // 请求体只有必要字段，验证可选图片字段可省略。
        JsonNode high = ok(call(HttpMethod.POST, "/api/user/reviews", user, Map.of(
                "rentalId", second, "rating", 5, "content", "五星好评")));
        // 上界评分同样原样落库，完成闭区间两端的覆盖。
        // 评分 5 是合法上界，与下界用例共同覆盖闭区间。
        assertEquals(5, high.get("data").get("rating").asInt(), high.toString());
    }

    /**
     * 场景：当前用户创建 3 条评价，另有他人 1 条评价。
     * 前置条件：工具评价是公开数据，我的评价按用户隔离。
     * 断言目标：我的评价总数 3 且只含本人记录；分页大小正确；
     * 工具评价总数 4 并可分两页读取，证明公开聚合与个人隔离并存。
     * 契约补充：分页参数 page 从 1 开始，size 表示每页条数；
     * 我的评价在 SQL 层按 userId 过滤，工具评价不做用户过滤。
     * 两种列表共用同一分页结构，前端可复用同一套渲染逻辑。
     * 总数与当前页条数分别断言，避免只验证其中一半。
     */
    @Test
    void user_and_tool_review_pages_are_paginated_and_owner_scoped() {
        // 当前用户连续创建 3 条评价，评分从 5 递减，便于区分记录。
        // 令牌在后续创建与查询中复用。
        String user = userLogin();
        // 读取种子工具行，三轮评价都挂在同一工具上。
        // 三条评价都挂在工具 1 上，便于公开列表聚合。
        Tool tool = toolMapper.selectById(1L);
        // 每轮即时创建一张已归还租赁单，满足一单一评。
        // 循环体每轮创建一张新租赁单，避免一单一评冲突。
        for (int i = 0; i < 3; i++) {
            // 第 i 条评价：租赁单即时创建，评分 5-i，内容带序号便于排查。
            // 请求成功后不解析响应，后续统一从列表校验。
            ok(call(HttpMethod.POST, "/api/user/reviews", user,
                    Map.of("rentalId", insertRental(1L, tool, "RETURNED"), "rating", 5 - i,
                            "content", "第 " + i + " 条")));
        }
        // 直接插入一条归属用户 2 的评价，制造“他人评价”对照。
        // 该评价走 Mapper 而非接口，避免为他人用户准备登录态。
        // 他人评价只影响工具公开列表，不应进入我的评价。
        insertReviewDirect(2L, tool, 3);

        // 我的评价按用户隔离：分页大小 2，总数应为 3。
        // 第一页请求 size=2，验证服务端切片而非前端截取。
        JsonNode firstPage = ok(call(HttpMethod.GET, "/api/user/reviews?page=1&size=2", user, null));
        // total 是过滤后的总数，不随分页大小变化。
        // 总数为 3，说明他人那条被用户维度过滤掉。
        assertEquals(3, firstPage.get("data").get("total").asInt(), firstPage.toString());
        // 当前页条数与页码元数据必须与请求参数一致。
        // 当前页 2 条，等于请求的 size。
        assertEquals(2, firstPage.get("data").get("records").size(), firstPage.toString());
        // 页码回显为 1，验证分页参数被正确解析。
        assertEquals(1, firstPage.get("data").get("page").asLong(), firstPage.toString());
        // 逐条校验 userId，确保列表没有混入他人评价。
        // 遍历当前页记录，逐条检查归属。
        for (JsonNode record : firstPage.get("data").get("records")) {
            // 越权数据一旦混入，这里会给出具体的记录内容。
            // userId 固定为 1，任何其他值都说明过滤条件失效。
            assertEquals(1L, record.get("userId").asLong(), record.toString());
        }

        // 第二页只剩 1 条，验证偏移量按 (page-1)*size 计算。
        // 第二页携带相同 size，只改变页码。
        JsonNode secondPage = ok(call(HttpMethod.GET, "/api/user/reviews?page=2&size=2", user, null));
        // 3 条数据分两页后剩余 1 条，偏移量计算正确。
        assertEquals(1, secondPage.get("data").get("records").size(), secondPage.toString());

        // 工具公开评价跨用户聚合：本人 3 条 + 他人 1 条 = 4 条。
        // 公开列表使用工具维度的 URL，不携带用户信息。
        JsonNode toolFirst = ok(call(HttpMethod.GET,
                "/api/user/tools/1/reviews?page=1&size=2", user, null));
        // 公开列表不按用户过滤，但首屏仍受分页大小限制。
        // 公开总数包含本人 3 条与他人 1 条。
        assertEquals(4, toolFirst.get("data").get("total").asInt(), toolFirst.toString());
        // 首屏 2 条，验证公开接口同样在服务端分页。
        // 首屏仍按 size=2 切片。
        assertEquals(2, toolFirst.get("data").get("records").size(), toolFirst.toString());
        // 第二页 2 条，说明公开聚合的分页连续且无重复。
        // 第二页从第 3 条开始，覆盖剩余的 2 条。
        JsonNode toolSecond = ok(call(HttpMethod.GET,
                "/api/user/tools/1/reviews?page=2&size=2", user, null));
        // 每页条数之和等于总数，说明分页无重复无遗漏。
        assertEquals(2, toolSecond.get("data").get("records").size(), toolSecond.toString());
    }

    /**
     * 场景：为两个工具分别插入 3 条与 1 条评价，查询工具列表。
     * 前置条件：列表接口需批量汇总评分，避免逐条查询。
     * 断言目标：各工具评分均值与数量独立正确；无评价工具返回 0 分、0 条，
     * 不因缺少评价而缺失。
     * 契约补充：批量汇总使用一次分组查询，避免逐工具查询的 N+1；
     * 没有评价的工具以 LEFT JOIN 保留，摘要字段默认为 0。
     * size=100 覆盖全部种子工具，断言不依赖工具总数。
     * 均值为 0.0 与计数为 0 要成对返回，前端无需额外判空。
     */
    @Test
    void tool_list_rating_summary_is_batched_per_tool() {
        // 两个工具分别构造 3 条与 1 条评价，验证批量汇总按工具分组。
        // 登录仅用于访问受保护的列表接口。
        String user = userLogin();
        // 读取前两个种子工具，作为有评价的对照组。
        // 第一个工具承载 3 条评价。
        Tool first = toolMapper.selectById(1L);
        // 第二个工具承载 1 条评价，用于对照分组聚合。
        Tool second = toolMapper.selectById(2L);
        // 工具 1：5/4/3 三条评价，均值应为 4.0。
        insertReviewDirect(1L, first, 5);
        // 5 星、4 星、3 星依次插入，均值只取决于数值总和。
        insertReviewDirect(1L, first, 4);
        // 三条记录归属同一工具，分组键必须相同。
        insertReviewDirect(1L, first, 3);
        // 工具 2：仅一条 2 星评价，均值应为 2.0。
        // 单条 2 星评价使该工具均值直接等于 2.0。
        insertReviewDirect(2L, second, 2);

        // size=100 足以覆盖全部种子工具，便于一次断言多个摘要。
        // 一次请求取回列表，避免逐工具查询。
        JsonNode list = ok(call(HttpMethod.GET, "/api/user/tools?size=100", user, null));
        // 在分页结果中按 ID 定位，避免依赖工具列表的默认排序。
        JsonNode firstListed = findById(list.get("data").get("records"), first.getId());
        // 第二个工具同样按 ID 定位。
        JsonNode secondListed = findById(list.get("data").get("records"), second.getId());
        // 工具 3 没有任何评价，用于验证零值分支。
        JsonNode thirdListed = findById(list.get("data").get("records"), 3L);
        // 三个工具都必须出现，包括从未被评价的工具 3。
        // 三个对象都非空，后续字段访问才不会空指针。
        assertTrue(firstListed != null && secondListed != null && thirdListed != null,
                list.toString());

        // 4.0 = (5+4+3)/3，验证均值按评价条数而非按用户汇总。
        // 均值使用 compareTo，避免 4.0 与 4.00 的表示差异。
        assertEquals(0, firstListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("4.0")), firstListed.toString());
        // 计数为 3，说明分母是评价条数而非用户数。
        assertEquals(3, firstListed.get("reviewCount").asInt(), firstListed.toString());
        // 工具 2 只有一条 2 星评价，均值为 2.0。
        // 单条 2 星评价的均值与计数同时校验。
        assertEquals(0, secondListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("2.0")), secondListed.toString());
        // 计数为 1，排除把其他工具评价计入的可能。
        assertEquals(1, secondListed.get("reviewCount").asInt(), secondListed.toString());
        // 无评价工具返回 0.0 分与 0 条，而不是 null 或字段缺失。
        // 无评价工具的默认摘要必须可序列化，不能是 null。
        assertEquals(0, thirdListed.get("ratingAvg").decimalValue()
                .compareTo(new BigDecimal("0.0")), thirdListed.toString());
        // 计数为 0 与均值 0.0 必须同时成立，避免前端除零展示异常。
        // 计数为 0 时前端可以安全地做除法与展示。
        assertEquals(0, thirdListed.get("reviewCount").asInt(), thirdListed.toString());
    }

    /**
     * 场景：按评分倒序查询工具列表，其中包含一个无评价工具。
     * 前置条件：工具 1 为 1 星、工具 2 为 5 星，工具 7 无评价。
     * 断言目标：5 星工具排在 1 星工具前；无评价工具仍保留在结果中，
     * 排序不能把无评价数据过滤掉。
     * 契约补充：orderBy=rating 时按均价倒序，无评价工具均价视为 0；
     * 排序只改变顺序，不得改变结果集大小，因此保留无评分工具。
     * category=WX 将对照限定在同一分类内，排除跨分类干扰。
     * 用相对位置而非绝对下标断言，种子数据增减时仍然稳定。
     */
    @Test
    void tool_list_can_sort_by_rating_and_keeps_unrated_tools() {
        // 工具 1 与工具 2 同属 WX 分类，分别构造低分与高分。
        // 登录仅用于访问工具列表接口。
        String user = userLogin();
        // 工具 1 作为低分对照，工具 2 作为高分对照。
        // 工具 1：1 星，作为排序中的低位。
        Tool low = toolMapper.selectById(1L);
        // 工具 2：5 星，作为排序中的高位。
        Tool high = toolMapper.selectById(2L);
        // 工具 7 不插入评价，用于验证无评分工具不会被排序过滤。
        // 工具 7：不插入评价，验证排序后的结果集完整性。
        Tool unrated = toolMapper.selectById(7L);
        // 低分为 1 星、高分为 5 星，差距足够大，排序结果确定。
        // 低分评价先插入，避免依赖插入顺序影响排序。
        insertReviewDirect(1L, low, 1);
        // 高分评价后插入，顺序与评分顺序相反，能识别错误排序。
        insertReviewDirect(1L, high, 5);

        // 按 rating 排序查询 WX 分类，验证排序键真正进入 SQL。
        // category 缩小结果集，orderBy 指定排序键。
        JsonNode list = ok(call(HttpMethod.GET,
                "/api/user/tools?category=WX&orderBy=rating&size=100", user, null));
        // 把结果 ID 抽成列表，便于比较相对位置与存在性。
        // 使用可变列表收集 ID，保持接口返回顺序。
        List<Long> ids = new ArrayList<>();
        // 遍历分页记录：列表顺序即接口返回顺序。
        // 不排序、不去重，直接映射为 ID 序列。
        list.get("data").get("records").forEach(node -> ids.add(node.get("id").asLong()));

        // 5 星工具必须排在 1 星工具之前，而非按创建时间排序。
        // indexOf 比较相对位置，比断言固定下标更稳健。
        assertTrue(ids.indexOf(high.getId()) < ids.indexOf(low.getId()),
                "评分高的工具应排在前面: " + list);
        // 无评价工具仍须保留，不能因按评分排序的 JOIN 被丢弃。
        // 无评价工具若被过滤，contains 会失败并打印完整响应。
        assertTrue(ids.contains(unrated.getId()),
                "无评价工具不能因为按评分排序被丢弃: " + list);
    }

    /**
     * 场景：管理员按工具筛选评价并查询全部评价。
     * 前置条件：用户已提交一条可见评价。
     * 断言目标：按工具筛选命中该条且内容正确；不筛选时总数至少为 1，
     * 管理端可审计评价内容。
     * 契约补充：管理端接口要求管理员令牌，普通用户令牌应被拒绝；
     * toolId 为可选过滤条件，不传时返回全量审计视图。
     * 列表记录保留评价内容，供人工复核与申诉处理。
     * 统计用 >=1 以兼容种子评价，只验证可见性而不锁定绝对数量。
     */
    @Test
    void admin_can_list_reviews_by_tool() {
        // 普通用户先创建一条评价，作为管理端审计的样本。
        // 样本评价必须由普通用户创建，才能覆盖真实写入链路。
        String user = userLogin();
        // 管理员使用独立登录端点，权限模型与用户端隔离。
        // 管理员令牌只用于管理端接口，不与用户令牌混用。
        String admin = adminLogin();
        // 工具行用于生成合法的已归还租赁单。
        Tool tool = toolMapper.selectById(1L);
        // 评价绑定当前用户刚创建的已归还租赁单。
        // 内容“管理端可见”便于在多家评价中识别本样本。
        ok(call(HttpMethod.POST, "/api/user/reviews", user,
                Map.of("rentalId", insertRental(1L, tool, "RETURNED"), "rating", 5,
                        "content", "管理端可见")));

        // 管理端按 toolId 过滤，应精确命中该工具的评价。
        // toolId 过滤是管理端最常用的审计入口。
        JsonNode list = ok(call(HttpMethod.GET,
                "/api/admin/reviews?toolId=1&page=1&size=10", admin, null));
        // 过滤后的总数与内容都要校验，防止只过滤计数而返回全量。
        // 若总数大于 1，说明过滤条件没有进入 SQL。
        assertEquals(1, list.get("data").get("total").asInt(), list.toString());
        // 内容用于确认返回的是同一条样本，而非其他工具的记录。
        // 内容断言确认命中样本而非其他工具的同分评价。
        assertEquals("管理端可见",
                list.get("data").get("records").get(0).get("content").asString(), list.toString());

        // 不带 toolId 时返回全量审计视图，至少包含刚才那条。
        // 不传过滤条件时返回全量视图，验证可选参数语义。
        JsonNode all = ok(call(HttpMethod.GET, "/api/admin/reviews?page=1&size=10", admin, null));
        // 用 >=1 而非 ==1，避免受其他用例或种子评价影响。
        // 使用 >=1 兼容其他用例留下的评价。
        assertTrue(all.get("data").get("total").asInt() >= 1, all.toString());
    }

    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    @Test
    void tool_reviews_require_login() {
        // 匿名请求不携带令牌，受保护的评价列表应直接拒绝。
        // token 传 null，请求头中不会出现 Authorization。
        ApiResponse response = call(HttpMethod.GET, "/api/user/tools/1/reviews", null, null);
        // 401 表示未认证，与 403 的“已认证但无权限”明确区分。
        // 401 来自安全过滤器，业务层不会被执行。
        assertEquals(401, response.status(), response.body().toString());
    }

    /**
     * 辅助方法：绕过接口直接插入评价，用于构造排序与聚合场景。
     * 使用独立的假租赁 ID 并登记到清理列表，避免与真实租赁冲突。
     * 直插评价不经过消毒与参数校验，只用于读路径场景；
     * 写路径契约由面向接口的用例覆盖。
     * 假租赁 ID 不参与业务校验，因此不会与真实订单状态冲突。
     */
    private void insertReviewDirect(long userId, Tool tool, int rating) {
        // 通过 Mapper 直接插入，绕过接口的一单一评与状态校验，
        // 用于快速构造排序、聚合等只关心读路径的场景。
        // 实体字段与表结构一一对应，插入即代表一条持久化评价。
        ToolReview review = new ToolReview();
        // 假租赁 ID 使用独立高位区段并自增，避免与真实租赁单冲突。
        // 序号参与假 ID，保证同一用例多次调用也互不冲突。
        long fakeRentalId = 900_000_000L + SEQ.incrementAndGet();
        // 假租赁 ID 只用于关联与清理，不对应真实租赁单。
        review.setRentalId(fakeRentalId);
        // 回填工具与用户的冗余展示字段，模拟真实评价记录。
        // 工具 ID 决定评价归属的工具维度。
        review.setToolId(tool.getId());
        // 工具名快照使聚合查询无需 JOIN 工具表。
        review.setToolName(tool.getName());
        // 用户 ID 决定评价在“我的评价”中的可见性。
        review.setUserId(userId);
        // 昵称快照与真实接口写入格式保持一致。
        review.setUserNickname("user_" + userId);
        // 固定评分；内容不影响排序与聚合断言。
        // 评分是排序与均值的输入，由调用方决定。
        review.setRating(rating);
        // 固定内容避免排序用例受文本差异影响。
        review.setContent("直接插入的评价");
        // 插入后登记假租赁 ID，cleanUp 会据此批量删除评价与假单。
        // 插入后立刻可被聚合查询读到。
        reviewMapper.insert(review);
        // 假租赁 ID 登记到清理列表，AfterEach 会删除对应评价。
        rentalIds.add(fakeRentalId);
    }

    /**
     * 辅助方法：断言参数校验失败返回 422 且业务码为 C400。
     * 统一校验错误契约，避免不同端点返回格式漂移。
     * 422 用于语义校验失败，HTTP 400 保留给请求格式错误；
     * 业务码 C400 说明问题出在业务参数而非传输层。
     * 三层语义分开后，前端可以按状态码决定提示方式。
     */
    private void assertUnprocessable(ApiResponse response) {
        // 语义校验失败统一返回 422，便于与 400 传输错误区分。
        assertEquals(422, response.status(), response.body().toString());
        // 参数错误统一为 HTTP 422 + 业务码 C400。
        // 业务码 C400 是参数错误，与 HTTP 422 配对使用。
        assertEquals("C400", response.body().get("code").asString(), response.body().toString());
        // 任何端点偏离该契约都会在断言处立即暴露。
    }

    /** 辅助方法：在分页 records 中按 ID 查找节点，找不到返回 null。 */
    private JsonNode findById(JsonNode records, long id) {
        // 线性扫描分页记录；工具数量很少，无需额外索引。
        // 记录数量很少，线性扫描足够。
        for (JsonNode record : records) {
            // 命中 ID 即返回整条记录，保留调用方需要的评分字段。
            // 只比较主键，忽略其他字段的差异。
            if (record.get("id").asLong() == id) {
                // 返回整条记录，调用方继续读取摘要字段。
                return record;
            }
        }
        // 未命中返回 null，由调用方断言语义。
        return null;
    }

    /**
     * 辅助方法：直接插入指定状态与归属的租赁单。
     * 根据状态同步押金状态与实际归还时间，使评价前置条件真实成立；
     * 单号与手机号带自增序号，避免唯一约束冲突。
     * 该辅助方法不校验工具是否存在，调用方需先读取种子工具；
     * 状态字段同时驱动押金状态与实际归还时间的组合。
     * 所有金额字段显式赋值，避免数据库默认值掩盖缺失字段。
     */
    private long insertRental(long userId, Tool tool, String status) {
        // 新实体从空对象开始，逐字段填充。
        Rental rental = new Rental();
        // 单号带固定前缀与自增序号，既便于清理也避免唯一键冲突。
        // 前缀加序号让清理条件可预测。
        rental.setOrderNo("IT-REVIEW-" + SEQ.incrementAndGet());
        // 下单时快照工具编号与名称，后续工具改名不影响历史订单。
        // 工具 ID、编号、名称构成下单快照。
        rental.setToolId(tool.getId());
        // 编号用于展示与检索，不参与关联。
        rental.setToolCode(tool.getCode());
        // 名称快照避免后续工具改名影响历史订单。
        rental.setToolName(tool.getName());
        // 租赁人信息使用固定测试值，手机号追加序号避免唯一约束。
        // 归属用户决定评价权限；用例可传 1 或 2。
        rental.setUserId(userId);
        // 租赁人姓名使用固定测试值，便于识别测试数据。
        rental.setRenterName("评价测试居民");
        // 手机号追加序号，避免同一批次内唯一约束冲突。
        rental.setRenterPhone("139000001" + String.format("%02d", SEQ.get() % 100));
        // 档期固定在两天前到一天前，保证已结束且不依赖当前时刻。
        // 去掉纳秒，避免数据库时间精度与断言不一致。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 开始时间在两天前，订单视为已开始。
        rental.setStartTime(now.minusDays(2));
        // 结束时间在一天前，订单视为已结束。
        rental.setEndTime(now.minusDays(1));
        // 取件人使用与租赁人相同的测试值。
        rental.setPickupPerson("评价测试居民");
        // 押金固定 100 元；已归还则退款完成，否则视为已支付。
        // 押金金额固定，退款断言可按该值计算。
        rental.setDepositAmount(new BigDecimal("100.00"));
        // 状态由调用方指定，评价用例只接受 RETURNED。
        // 已归还则押金已退，否则仍在支付态。
        rental.setDepositStatus("RETURNED".equals(status) ? "REFUNDED" : "PAID");
        // 状态由调用方指定，是评价校验的核心变量。
        rental.setStatus(status);
        // 已归还订单必须带实际归还时间，未归还保持为空。
        // 实际归还时间只在 RETURNED 时存在，保持数据自洽。
        rental.setActualReturnTime("RETURNED".equals(status) ? now.minusDays(1) : null);
        // 逾期费、损坏费与退款额清零，避免干扰评价前置条件。
        // 逾期费为零，排除额外费用分支。
        rental.setOverdueFee(BigDecimal.ZERO);
        // 损坏费为零，避免触发报修或扣款逻辑。
        rental.setDamageFee(BigDecimal.ZERO);
        // 退款额为零，未归还订单没有退款。
        rental.setRefundAmount(BigDecimal.ZERO);
        // 插入后登记 ID，供 cleanUp 统一删除。
        // 插入后由数据库生成主键。
        rentalMapper.insert(rental);
        // 主键登记到清理列表。
        rentalIds.add(rental.getId());
        // 返回主键供评价接口直接使用。
        return rental.getId();
    }

    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    private JsonNode ok(ApiResponse response) {
        // 成功响应必须同时满足 HTTP 200 与业务码 A200。
        // HTTP 200 表示请求被正常处理。
        assertEquals(200, response.status(), response.body().toString());
        // 返回 body 便于继续断言 data 内部字段。
        // A200 是业务成功码，与 HTTP 状态独立。
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        // 返回原始 body，调用方按需读取 data。
        return response.body();
    }

    /** 辅助方法：种子用户密码登录并返回令牌。 */
    private String userLogin() {
        // 种子用户手机号与密码来自启动种子数据，测试不自行注册。
        // 登录端点与种子账号固定，便于环境复现。
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /** 辅助方法：种子管理员登录并返回令牌。 */
    private String adminLogin() {
        // 管理员使用独立登录端点，返回同样的 tokenValue 结构。
        // 管理员账号来自启动种子，不在此处创建。
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    private String login(String url, Map<String, Object> body) {
        // 登录请求为 POST + JSON，不带既有令牌。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        // 登录失败时断言会带上响应体，便于定位种子数据缺失。
        // 状态码失败时打印响应体，快速定位种子缺失。
        assertEquals(200, response.status(), response.body().toString());
        // tokenValue 由 Sa-Token 生成，后续放在 Bearer 头中。
        // 取出 tokenValue，作为后续 Bearer 令牌。
        return response.body().get("data").get("tokenValue").asString();
    }

    /**
     * 辅助方法：发送可带令牌的 JSON 请求，返回状态码与解析后的响应体。
     * 不因 4xx 抛异常，便于校验权限与参数错误。
     * exchange 的回调在响应到达后执行，读流失败会转成
     * IllegalStateException，让测试立即失败而不是静默返回空体。
     * 空响应体统一解析为 {}，调用方无需额外判空。
     */
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 使用 RestClient 的 exchange 而非 retrieve，4xx 不抛异常，
        // 这样权限与参数错误都能被当作普通响应断言。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 匿名请求不附加认证头。
        if (token != null) {
            // 有令牌时附加 Bearer 头；匿名用例传 null 以验证 401。
            // Authorization 采用标准 Bearer 方案。
            spec.header("Authorization", "Bearer " + token);
        }
        // GET 请求没有 body，跳过序列化。
        if (body != null) {
            // 有请求体时声明 JSON 类型，RestClient 负责序列化。
            // 有 body 时显式声明 JSON，避免被当作表单。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 手动读取响应流：空体统一解析为 {}，保证后续 get 不空指针。
        // exchange 允许读取任意状态码的响应。
        return spec.exchange((request, response) -> {
            // try-with-resources 确保响应流关闭。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取，中文内容不会乱码。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 状态码保留原始值，业务码从 body.code 读取，二者独立断言。
                // 业务码与 HTTP 状态分别保留，便于双层断言。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 解析失败视为测试基础设施错误，直接抛出。
            } catch (IOException e) {
                // 保留原始异常作为 cause，便于定位。
                throw new IllegalStateException(e);
            }
        });
    }
}
