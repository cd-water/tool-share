package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报修查询集成测试。
 * <p>
 * 测试范围：居民按报修单号或工具编号检索、状态筛选、服务端分页与归属校验；
 * 管理端关键字与状态的组合筛选，以及列表中的工具主图回填。
 * 依赖环境：随机端口完整应用、测试数据库、内存对象存储与种子工具/用户。
 * 关键不变量：居民关键字不扩大到工具名称；只能查看自己的报修单，
 * 他人单 403、不存在 404；分页上限 100；管理端关键字可命中单号、
 * 工具编号、工具名与报修人，并能与状态条件叠加。
 * 覆盖接口：GET /api/user/damage-reports（居民列表与检索）、
 * GET /api/user/damage-reports/{id}（居民详情）、
 * GET /api/admin/damage-reports（管理端组合筛选）。
 * 关键词匹配范围在居民端与管理端刻意不同，是本类重点验证的契约。
 * 断言风格：直接比较 ID 集合与分页元数据，不依赖实体字段顺序；
 * 错误场景同时校验 HTTP 状态与业务码，防止两层语义脱节。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class DamageQueryIntegrationTest extends AbstractIntegrationTest {

    // 报修单号统一前缀，清理时按前缀右匹配，避免影响其他测试数据。
    private static final String PREFIX = "IT-DQRY-";
    // 序号只增不减，测试之间不共享状态。
    // 自增序号保证单号与租赁 ID 唯一，支持重复执行与并行执行。
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 随机端口，供测试 HTTP 客户端使用。 */
    @LocalServerPort
    int port;

    /** 报修 Mapper，用于构造查询数据与清理。 */
    @Autowired
    // Mapper 同时用于准备数据、读取单号与清理。
    DamageReportMapper damageReportMapper;
    /** 工具 Mapper，用于读取种子工具主图做对照。 */
    @Autowired
    ToolMapper toolMapper;

    // 每个用例按随机端口重建客户端，避免连接状态跨用例泄漏。
    // 客户端只负责发请求，不缓存令牌或响应。
    RestClient client;
    // 响应统一解析为 JsonNode，断言字段路径而不依赖实体类。
    final ObjectMapper om = new ObjectMapper();

    // HTTP 结果载体：同时保留状态码与解析后的 JSON。
    // 状态码与 body 分开保存，断言时可输出完整上下文。
    record ApiResponse(int status, JsonNode body) {
    }

    /** 每个用例前创建指向随机端口的 HTTP 客户端。 */
    @BeforeEach
    void setUp() {
        // 随机端口由 Spring 分配，客户端必须指向它才能命中被测应用。
        // baseUrl 必须以 http:// 开头，RestClient 不接受裸主机名。
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** 用例结束后按报修单号前缀清理本类插入的报修记录。 */
    @AfterEach
    void cleanUp() {
        // 按单号前缀清理本类插入的报修记录，不触碰种子或其他用例数据。
        // likeRight 生成 PREFIX%，只匹配本类前缀。
        damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                .likeRight(DamageReport::getReportNo, PREFIX));
    }

    /**
     * 场景：用户用报修单号、工具编号与工具名称分别检索自己的报修记录。
     * 前置条件：三条报修分属同一用户，但工具编号或名称不同。
     * 断言目标：单号与工具编号都能精确命中唯一记录；
     * 用工具名称检索返回空，说明居民关键字范围被限制，
     * 不把工具名称等非归属字段纳入搜索，防止侧信道枚举。
     * 契约补充：居民端关键词只匹配报修单号与工具编号，
     * 不匹配工具名称、报修人等其他字段，避免越权枚举他人数据。
     * 三条记录归属同一用户，排除归属过滤对结果的干扰。
     * 边界说明：关键词为空时返回本人全量列表，本用例不覆盖该分支；
     * 单号与编号的匹配均为包含匹配，因此使用完整值以缩小结果。
     * 若将来加入报修人搜索，必须同步更新居民端的字段边界测试。
     * 结果集使用精确相等而非包含，确保没有额外字段参与匹配。
     * 该用例是居民端与管理端搜索范围差异的核心证据。
     */
    @Test
    void user_searches_own_reports_by_report_no_or_tool_code() {
        // 登录种子用户，所有查询都以该用户身份发起。
        // 登录接口返回令牌字符串，不返回用户实体。
        String user = userLogin();
        // 第一条：单号可命中，用于验证单号的精确检索。
        // 第一条的工具编号“IT-DQRY-CODE-NO”只用于展示，不是检索目标。
        long byNo = insertReport(1L, 1L, "IT-DQRY-CODE-NO", "冲击钻", "王建国",
                "SUBMITTED", "no-match");
        // 第二条：工具编号可命中，工具编号与单号使用不同前缀。
        // 第二条的工具编号恰好包含 CODE-CODE，用于编号检索。
        long byCode = insertReport(1L, 2L, "IT-DQRY-CODE-CODE", "工具箱", "王建国",
                "SUBMITTED", "code-match");
        // 第三条：工具名“切割机”仅出现在名称字段，用于验证不匹配。
        // 该条故意不使用编号“CODE”，确保工具名检索不会误命中。
        insertReport(1L, 1L, "IT-DQRY-CODE-OTHER", "切割机", "王建国", "SUBMITTED", "other");

        // 用完整单号检索，应只返回第一条。
        // size=100 确保结果不会被默认分页截断。
        List<Long> noResult = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=" + reportNo(byNo) + "&size=100", user)));
        // 结果集合精确等于单条 ID，排除模糊匹配带来的多条。
        // List.of 顺序敏感：结果既不能多也不能换序。
        assertEquals(List.of(byNo), noResult);

        // 用工具编号检索，应只返回第二条。
        // 工具编号是业务可读编码，居民可以据此定位自己的报修。
        // 查询参数直接拼在 URL 中，服务端按关键字做前缀/包含匹配。
        List<Long> codeResult = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-CODE-CODE&size=100", user)));
        // 结果同样精确为单条，说明编号匹配不扩大范围。
        // 编号命中第二条而非第一条，说明匹配依据是编号字段。
        assertEquals(List.of(byCode), codeResult);

        // 居民端关键字仅覆盖报修单号与工具编号，不扩大到工具名称；
        // 这是防止通过工具名枚举他人报修的边界。
        // 居民端关键字仅覆盖报修单号与工具编号，不扩大到工具名称。
        // 居民关键字仅覆盖报修单号与工具编号，不扩大到工具名
        // 用工具名检索返回空，证明匹配字段被严格限制。
        // 工具名只存在于名称字段，若命中则说明搜索范围越界。
        // isEmpty 比 size==0 更直接表达“不允许任何结果”。
        assertTrue(ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=切割机&size=100", user))).isEmpty());
    }

    /**
     * 场景：用户创建两条待提交与一条已完成的报修，按状态与分页查询。
     * 前置条件：列表接口在服务端分页，不依赖前端切片。
     * 断言目标：状态筛选只返回待提交记录；第一页两条、第二页一条；
     * total 反映筛选后的总数；size=200 被收敛为 100，
     * 避免大分页拖垮数据库。
     * 契约补充：分页在数据库层完成，page 从 1 开始，size 为每页条数；
     * 服务端把 size 上限固定为 100，防止大分页拖垮数据库。
     * 状态筛选与关键词条件可叠加，total 反映叠加后的总数。
     * 边界说明：page 小于 1 时服务端按第一页处理，本用例不覆盖；
     * size 小于 1 时按默认值处理，同样不属于本用例范围。
     * 分页参数由查询字符串传入，不依赖请求体。
     * 第一页与第二页的断言成对出现，能同时发现重复与遗漏。
     * size 上限收敛属于防御性逻辑，单独用越界值验证。
     */
    @Test
    void user_filters_by_status_and_paginates_server_side() {
        // 登录用户，后续创建与查询都使用该身份。
        // 三条数据都归属当前用户，状态是唯一变量。
        String user = userLogin();
        // 第一条 SUBMITTED，用于状态筛选的正向样本。
        // page-a 的编号后缀便于在失败输出中定位记录。
        long first = insertReport(1L, 1L, "IT-DQRY-PAGE-A", "电钻", "王建国",
                "SUBMITTED", "page-a");
        // 第二条 SUBMITTED，与第一条共同构成分页数据。
        // page-b 使用不同工具，验证筛选不依赖工具维度。
        long second = insertReport(1L, 2L, "IT-DQRY-PAGE-B", "电钻", "王建国",
                "SUBMITTED", "page-b");
        // 第三条 COMPLETED，用于验证被状态条件排除。
        // COMPLETED 记录用于证明状态条件确实生效。
        long completed = insertReport(1L, 1L, "IT-DQRY-PAGE-C", "电钻", "王建国",
                "COMPLETED", "page-c");

        // 关键词 + 状态组合查询，结果应只含两条待提交记录。
        // 关键词 IT-DQRY-PAGE 覆盖全部三条，再叠加 SUBMITTED。
        List<Long> submitted = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&status=SUBMITTED&size=100", user)));
        // 两条 SUBMITTED 都必须命中。
        // containsAll 不要求顺序，只要求两条都在。
        assertTrue(submitted.containsAll(List.of(first, second)), submitted.toString());
        // COMPLETED 记录不能出现在结果中。
        // 已完成记录一旦出现，就说明状态条件被忽略。
        assertFalse(submitted.contains(completed), submitted.toString());

        // 第一页 size=2，验证服务端切片而不是前端截取。
        // 第一页从第 1 条开始，offset 为 0。
        JsonNode pageOne = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&page=1&size=2", user));
        // 第一页应返回 2 条记录。
        // 记录数等于 min(size, 剩余条数)，此时为 2。
        assertEquals(2, pageOne.get("data").get("records").size());
        // size 回显为 2，说明分页参数被正确解析。
        // 回显 size 让前端确认服务端接受了自己的分页参数。
        assertEquals(2, pageOne.get("data").get("size").asLong());
        // total 为 3，是过滤后的总数，不随分页变化。
        // total 不受 size 影响，始终是过滤后的 3 条。
        assertEquals(3, pageOne.get("data").get("total").asLong());
        // 第二页只应返回剩余 1 条。
        // 第二页只请求剩余数据，offset 为 2。
        JsonNode pageTwo = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&page=2&size=2", user));
        // 1 条的结果验证偏移量按 (page-1)*size 计算。
        // 第二页 1 条说明没有重复返回第一页数据。
        assertEquals(1, pageTwo.get("data").get("records").size());

        // 超大分页大小应被服务端收敛到 100。
        // size=200 超过服务端上限，实际返回的 size 必须是 100。
        // 关键词保持不变，只把 size 放大到越界值。
        JsonNode clamped = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&size=200", user));
        // 收敛后的 size 回显 100，前端据此调整分页器。
        // 上限收敛是服务端防御，不能依赖客户端自觉。
        assertEquals(100, clamped.get("data").get("size").asLong());
    }

    /**
     * 场景：当前用户查询自己的报修列表，并尝试读取他人报修详情。
     * 前置条件：一条报修归属用户 1，另一条归属用户 2。
     * 断言目标：列表只包含本人记录；他人详情返回 403/C403；
     * 不存在的 ID 返回 404/C404，区分越权与资源缺失。
     * 契约补充：列表在 SQL 层按 userId 过滤；
     * 详情接口先查资源再判归属，他人资源返回 403 而非 404。
     * 不存在的 ID 返回 404，与越权场景分开提示。
     * 边界说明：列表接口返回 total 与 records，详情接口返回单条；
     * 两者共用同一套归属校验，错误码保持一致。
     * 越权详情不返回任何字段，避免通过错误信息泄露数据。
     * 404 分支使用极大 ID，避免与种子数据或测试数据碰撞。
     * 列表断言使用 contains，允许种子报修同时存在。
     */
    @Test
    void user_cannot_see_other_residents_reports() {
        // 登录用户 1，其名下只有 first 这条报修。
        // 后续所有断言都基于用户 1 的视角。
        String user = userLogin();
        // 本人报修：应出现在列表中并可读取详情。
        // own 的归属与登录用户一致。
        long own = insertReport(1L, 1L, "IT-DQRY-CODE-OWN", "手电钻", "王建国",
                "SUBMITTED", "own");
        // 他人报修：归属用户 2，不应出现在本人列表。
        // others 的工具与状态都合法，唯一差异是归属用户。
        long others = insertReport(2L, 2L, "IT-DQRY-CODE-OTHERS", "手电钻", "李四",
                "SUBMITTED", "others");

        // 查询本人列表，验证用户维度过滤。
        // size=100 覆盖本用户全部报修，避免分页干扰。
        List<Long> list = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?size=100", user)));
        // 本人的报修必须在列表中。
        // 列表包含本人记录，说明归属过滤没有误伤。
        assertTrue(list.contains(own), list.toString());
        // 他人的报修不能混入，否则属于数据越权。
        // 他人记录不出现，说明过滤条件进入 SQL。
        assertFalse(list.contains(others), list.toString());

        // 直接读取他人报修详情，触发归属校验。
        // 详情 URL 直接使用他人报修 ID，模拟越权访问。
        ApiResponse forbidden = call(HttpMethod.GET, "/api/user/damage-reports/" + others, user);
        // 403 表示已认证但无权访问该资源。
        // 403 而不是 404，说明服务端确认资源存在但拒绝访问。
        assertEquals(403, forbidden.status(), forbidden.body().toString());
        // 业务码 C403 与 HTTP 403 对应，便于前端统一提示。
        // 业务码让前端能区分“无权限”与“参数错误”。
        assertEquals("C403", forbidden.body().get("code").asString());

        // 读取不存在的 ID，验证资源缺失分支。
        // 使用极大 ID，确保数据库中没有对应记录。
        ApiResponse missing = call(HttpMethod.GET, "/api/user/damage-reports/999999999", user);
        // 404 与 403 区分，避免把“不存在”误报为“无权限”。
        // 404 表示资源缺失，与越权语义分开。
        assertEquals(404, missing.status(), missing.body().toString());
        // 业务码 C404 用于前端跳转到列表页或提示单号错误。
        // 业务码 C404 与 HTTP 404 一致。
        assertEquals("C404", missing.body().get("code").asString());
    }

    /**
     * 场景：管理员分别用工具名、工具编号、报修人、报修单号检索，
     * 再叠加状态条件查询。
     * 前置条件：两条报修分别处于 SUBMITTED 与 COMPLETED。
     * 断言目标：管理端关键字覆盖上述四类字段；
     * 关键字与状态组合时两个条件同时生效；
     * 不匹配的组合返回空，避免管理端筛选条件被忽略。
     * 契约补充：管理端关键词覆盖单号、工具编号、工具名与报修人；
     * 状态条件与关键词是 AND 关系，任一条件不满足即不返回。
     * 管理端没有居民端的字段限制，因为管理员本就拥有全量数据权限。
     * 边界说明：管理端关键字为空时返回全量，本用例只验证非空关键字；
     * 状态条件使用枚举字符串，非法值由参数校验拦截。
     * 四种字段的命中结果都使用 contains 断言，允许其他测试数据共存。
     * 组合查询使用短关键词覆盖两条记录，再用状态精确排除一条。
     * 结果为空的分支验证两个条件确实同时生效。
     */
    @Test
    void admin_combines_keyword_and_status_filters() {
        // 管理端令牌来自独立登录端点，权限更高。
        // 管理员登录，管理端接口使用独立令牌。
        String admin = adminLogin();
        // alpha：待提交，工具名含 alpha，报修人“甲”。
        // alpha 的关键词同时出现在工具名与报修人字段之外的工具名中。
        long alpha = insertReport(1L, 1L, "IT-DQRY-ADM-A", "IT-DQRY-冲击钻-alpha", "甲",
                "SUBMITTED", "admin-a");
        // beta：已完成，工具名含 beta，报修人“乙”。
        // beta 的状态为 COMPLETED，用于组合条件排除。
        long beta = insertReport(2L, 2L, "IT-DQRY-ADM-B", "IT-DQRY-切割机-beta", "乙",
                "COMPLETED", "admin-b");

        // 用工具名片段检索：管理端允许匹配工具名称。
        // keyword=alpha 应匹配工具名中的片段。
        List<Long> byToolName = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=alpha&size=100", admin)));
        // 应命中 alpha 这条报修。
        // 命中 alpha 证明工具名参与管理端搜索。
        assertTrue(byToolName.contains(alpha), byToolName.toString());
        // 不应命中 beta，验证关键词确实按名称过滤。
        // 不命中 beta 证明结果不是全量返回。
        assertFalse(byToolName.contains(beta), byToolName.toString());

        // 用工具编号检索，命中 beta 这条报修。
        // 用 beta 的工具编号检索，覆盖编号字段。
        List<Long> byToolCode = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=IT-DQRY-ADM-B&size=100", admin)));
        // 工具编号与单号不同，单独覆盖编号字段。
        // 编号命中 beta，验证查询列包含工具编号。
        assertTrue(byToolCode.contains(beta), byToolCode.toString());

        // 用报修人姓名检索，验证管理端可按键索人。
        // 用中文姓名检索，验证 UTF-8 参数传递正确。
        List<Long> byReporter = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=乙&size=100", admin)));
        // 报修人“乙”应命中 beta。
        // 报修人字段参与管理端关键字匹配。
        assertTrue(byReporter.contains(beta), byReporter.toString());

        // 用完整报修单号检索，验证精确匹配路径。
        // 单号通过 reportNo 动态读取，避免硬编码。
        List<Long> byReportNo = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=" + reportNo(alpha) + "&size=100", admin)));
        // 单号命中 alpha，与编号检索形成互补。
        // 单号命中 alpha，证明精确匹配路径可用。
        assertTrue(byReportNo.contains(alpha), byReportNo.toString());

        // 关键词与状态叠加：只应返回已完成的 beta。
        // 关键词 IT-DQRY- 同时覆盖两条，再用状态收窄。
        List<Long> combined = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=IT-DQRY-&status=COMPLETED&size=100", admin)));
        // 组合条件同时生效时 beta 命中。
        // beta 状态为 COMPLETED，组合条件应命中。
        assertTrue(combined.contains(beta), combined.toString());
        // alpha 因状态不符被排除，证明条件之间是 AND。
        // alpha 状态为 SUBMITTED，组合条件应排除。
        assertFalse(combined.contains(alpha), combined.toString());

        // 关键词命中 alpha 但状态要求 COMPLETED，组合结果应为空。
        // 关键词与状态相互矛盾，结果必须为空集。
        assertTrue(ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=alpha&status=COMPLETED&size=100", admin)))
                // 空集证明两个条件都生效，而非只取其中一个。
                .isEmpty());
    }

    /**
     * 场景：查询报修列表并检查工具主图字段。
     * 前置条件：种子工具已配置主图。
     * 断言目标：列表记录带 toolImageUrl，且与工具表主图一致、非空白，
     * 保证前端无需二次请求即可展示缩略图。
     * 契约补充：列表记录冗余返回 toolImageUrl，避免前端逐条查询工具表；
     * 主图取自工具表，必须与当前工具主图一致且非空白。
     * 该字段为展示优化，不影响报修单本身的业务字段。
     * 参数均允许为空字符串，测试用例会显式传入有意义的值。
     * 插入后不刷新缓存，查询直接读数据库，保证断言看到最新数据。
     * 单号前缀由类常量统一维护，清理逻辑无需感知调用方后缀。
     */
    @Test
    void list_includes_tool_main_image() {
        // 登录用户，查询自己的报修列表。
        // 查询范围限定为当前用户自己的报修。
        String user = userLogin();
        // 插入一条报修，工具指向种子工具 1。
        // 工具指向种子工具 1，主图字段已由种子数据配置。
        long report = insertReport(1L, 1L, "IT-DQRY-CODE-IMG", "手电钻", "王建国",
                "SUBMITTED", "img");

        // 按单号精确定位记录，取第一条即可。
        // 单号来自刚落库的记录，保证唯一定位。
        JsonNode record = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=" + reportNo(report) + "&size=100", user))
                // records 至少有一条：关键字命中刚插入的单号。
                .get("data").get("records").get(0);
        // 字段必须存在，否则前端缩略图会空指针。
        // 断言消息带上工具实体与记录，失败时可人工比对。
        assertNotNull(record.get("toolImageUrl"), toolMapper.selectById(1L) + " " + record);
        // 与工具表主图逐字比较，验证冗余字段来源正确。
        // 两次 selectById 读取的是同一行种子工具。
        assertEquals(toolMapper.selectById(1L).getImageUrl(),
                record.get("toolImageUrl").asString(), record.toString());
        // 非空白校验排除空字符串占位。
        // isBlank 同时排除 null 之外的空白字符串。
        assertFalse(record.get("toolImageUrl").asString().isBlank(), record.toString());
    }

    /**
     * 辅助方法：直接插入指定归属、工具、报修人与状态的报修记录。
     * 单号带自增序号，租赁 ID 使用独立区段，避免与真实数据冲突。
     * 只做最小字段填充，未设置的列使用数据库默认值。
     * 该方法不返回值，主键由调用方按需再查。
     * 报修人、损坏部位与描述使用固定值，便于在失败输出中识别。
     */
    private long insertReport(Long userId, Long toolId, String toolCode, String toolName,
                              String reporterName, String status, String suffix) {
        // 实体不设置创建时间等审计字段，由数据库默认值补齐。
        DamageReport report = new DamageReport();
        // 单号由前缀、自增序号与调用方后缀组成：
        // 前缀用于清理，序号保证唯一，后缀便于人工识别场景。
        // 自增序号在单号中只出现一次，清理时前缀仍可匹配。
        report.setReportNo(PREFIX + SEQ.incrementAndGet() + "-" + suffix);
        // 租赁 ID 使用独立区段，避免与真实租赁单冲突。
        // 每个用例最多插入几条，该区段不会与其他测试重叠。
        // 租赁 ID 与真实租赁表无外键关联，仅作为快照字段。
        report.setRentalId(900000L + SEQ.get());
        // 工具 ID 决定关联工具，影响主图回填。
        // 工具 ID 用于列表聚合主图时的关联键。
        report.setToolId(toolId);
        // 工具编号是居民可检索字段，也是下单快照。
        // 工具编号与单号使用不同的前缀，便于区分检索命中来源。
        report.setToolCode(toolCode);
        // 工具名称仅管理端可检索，用于验证两端范围差异。
        // 工具名包含“冲击钻/工具箱/切割机”，由各个用例自行指定。
        report.setToolName(toolName);
        // 归属用户决定列表可见性与详情权限。
        // 用户 1 是种子居民，用户 2 用于构造他人数据。
        report.setUserId(userId);
        // 报修人姓名用于管理端关键字匹配。
        // 报修人姓名可与工具名不同，用于验证管理端字段覆盖。
        report.setReporterName(reporterName);
        // 手机号固定，测试不校验联系方式唯一性。
        // 固定手机号不会触发唯一约束，因为表未要求手机号唯一。
        report.setReporterPhone("13900000001");
        // 损坏部位与描述使用固定值，测试只关心查询条件。
        // 损坏部位与描述是必填展示字段，固定值便于阅读。
        report.setDamagePart("电源接口");
        // 描述不参与检索，只作为报修内容留存。
        report.setDescription("通电后无反应");
        // 空图片串模拟未上传图片的报修。
        // 图片字段为空串，与“未上传”语义一致。
        report.setImages("");
        // 状态由调用方指定，是筛选用例的核心变量。
        // 状态值需与枚举字符串完全一致，服务端按原值过滤。
        report.setStatus(status);
        // 插入后返回主键，供后续按 ID 断言。
        // insert 后实体主键由 MyBatis-Plus 回填。
        damageReportMapper.insert(report);
        // 返回主键，调用方无需再次查询。
        return report.getId();
    }

    /** 辅助方法：按 ID 读取报修单号，用于构造关键字查询。 */
    // 先按 ID 查询实体，再取单号，保证关键字来自真实落库值。
    // 该方法只用于构造关键字，不做空值兜底。
    private String reportNo(long id) {
        // 查询不到时返回 null，调用方会立即空指针暴露问题。
        return damageReportMapper.selectById(id).getReportNo();
    }

    /** 辅助方法：从分页响应中提取记录 ID 列表。 */
    // 从 data.records 提取 ID，忽略分页元数据。
    // body 必须是成功的分页响应，否则 records 路径为 null。
    private List<Long> ids(JsonNode body) {
        // 保持接口返回顺序，便于位置相关断言。
        // 结果列表容量很小，无需预估大小。
        List<Long> result = new ArrayList<>();
        // 遍历记录并读取主键字段。
        // 只读取 data.records，忽略 page/size/total 元数据。
        for (JsonNode record : body.get("data").get("records")) {
            // ID 为长整型，避免前端数字精度问题在测试中被放大。
            result.add(record.get("id").asLong());
        }
        return result;
    }

    /** 辅助方法：断言 HTTP 200 与业务码 A200，返回响应体。 */
    // 成功响应必须同时满足 HTTP 200 与业务码 A200。
    // 该辅助方法只用于期望成功的请求。
    private JsonNode ok(ApiResponse response) {
        // 返回 body 便于继续读取 data。
        // 先校验 HTTP 状态，再校验业务码。
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        // 返回 body，调用方继续读取 data。
        return response.body();
    }

    /** 辅助方法：种子管理员登录并返回令牌。 */
    // 管理员账号来自启动种子数据。
    // 管理员用户名固定为 admin，测试不创建新管理员。
    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    /** 辅助方法：种子用户密码登录并返回令牌。 */
    // 居民账号来自启动种子数据，测试不自行注册。
    // 手机号与密码与启动种子保持一致。
    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    /** 辅助方法：调用登录接口，断言成功并取出 tokenValue。 */
    // 登录请求为 POST + JSON，不带既有令牌。
    // 登录响应结构与业务接口一致，tokenValue 位于 data 下。
    private String login(String url, Map<String, Object> body) {
        // 登录请求不携带旧令牌。
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        // 失败时打印响应体，快速定位种子数据缺失。
        // 登录失败通常意味着种子数据未初始化。
        assertEquals(200, response.status(), response.body().toString());
        // tokenValue 作为后续 Bearer 令牌。
        // 取出令牌字符串，后续直接拼入 Bearer 头。
        return response.body().get("data").get("tokenValue").asString();
    }

    /** 辅助方法（重载）：无请求体的 GET 请求，复用四参数实现。 */
    // 无请求体的 GET 复用四参数实现，统一响应解析。
    // 重载方法避免 GET 调用方传多余的 null。
    private ApiResponse call(HttpMethod method, String url, String token) {
        // body 传 null，四参数实现会跳过 JSON 序列化。
        return call(method, url, token, null);
    }

    /** 辅助方法：发送可带令牌的 JSON 请求，返回状态码与响应体。 */
    // exchange 允许读取任意状态码，4xx 不抛异常。
    // 返回自定义 ApiResponse，而不是 Spring 的 ResponseEntity。
    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        // 先构造请求规格，再按需追加头与 body。
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        // 有令牌时附加标准 Bearer 认证头；匿名用例传 null 跳过。
        // 认证头只在有令牌时添加，匿名请求保持干净。
        if (token != null) {
            // Bearer 方案与 Sa-Token 的令牌解析约定一致。
            spec.header("Authorization", "Bearer " + token);
        }
        // 有 body 时声明 JSON 类型；GET 请求没有 body，跳过序列化。
        // 没有 body 的请求（GET/DELETE）不设置 Content-Type。
        if (body != null) {
            // body 对象由 Jackson 序列化为 JSON。
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 手动读取响应流，空体统一解析为 {}。
        // exchange 的回调只在响应到达后执行。
        return spec.exchange((request, response) -> {
            // 响应流必须关闭，try-with-resources 保证异常路径也释放。
            try (InputStream in = response.getBody()) {
                // 按 UTF-8 读取，中文关键字与内容不会乱码。
                // 空体（如 204）在下一步统一替换为 {}。
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 状态码与解析后的 JSON 一起返回，供双层断言。
                // 状态码保留原始数字，便于断言 401/403/404。
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            // 读流或解析异常属于测试基础设施问题，直接上抛。
            } catch (IOException e) {
                // 包装为运行时异常，避免在 lambda 中声明受检异常。
                throw new IllegalStateException(e);
            }
        });
    }
}
