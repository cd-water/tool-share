package com.cdwater.toolshare.module.ai;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.review.ToolRatingStats;
import com.cdwater.toolshare.module.review.ToolReviewMapper;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.web.BizException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具推荐服务单元测试。
 * <p>
 * 测试范围：向量召回上限、关键词兜底边界、工具可用性与条件过滤、
 * 大模型重排与降级、推荐历史落库、点击/反馈归属校验，以及外部依赖异常隔离。
 * 依赖环境：Mockito 模拟 Mapper、向量库与 ChatClient；用 Sa-Token Mock 上下文提供用户身份。
 * 关键不变量：召回与提示词都有硬上限；兜底查询必须带 LIMIT 且不扫全表；
 * 只推荐审核通过、可用、符合分类/押金/档期的工具；
 * 任意 AI 或数据库异常都降级为可用结果，不把异常抛给前端；
 * <p>
 * 测试策略：以推荐入口为黑盒，通过 mock 控制召回、过滤、重排与落库。
 * 每个用例只改变一个变量，失败时能快速定位到具体阶段。
 * 断言既检查响应结构，也检查落库字段与真实 SQL 片段。
 * 权限相关用例在 Sa-Token Mock 上下文中执行，避免依赖真实登录。
 * 所有外部依赖都可注入 null，用于验证降级而非仅验证成功路径。
 * 该文件同时承担“性能上限”与“权限边界”两类非功能契约。
 * 新增推荐策略时，应优先补充边界与降级用例，而非只测正常排序。
 * 测试不调用真实向量库或大模型，可离线稳定运行。
 * 推荐历史、点击与反馈都属于辅助数据，主流程不因其失败而不可用。
 * <p>
 * 维护提示：调整召回上限、过滤条件或提示词模板时，应同步更新对应断言；
 * 只改实现而不改本类，通常意味着某条非功能契约被悄悄放宽。
 * 断言失败信息尽量携带实际值（SQL、topK、prompt 长度），减少排查成本。
 * 该文件全部使用 Mockito，不需要数据库、Redis、向量库或模型服务。
 * 点击与反馈只能操作本人的推荐记录。
 */
class ToolRecommendServiceTest {

    /** 单元测试中模拟登录的用户 ID。 */
    private static final long USER_ID = 7L;

    /** 工具 Mapper 测试替身。 */
        // 工具查询替身：控制候选集合与兜底 SQL。
        // 默认不设置返回值，由各用例按需 stub。
    private ToolMapper toolMapper;
    /** 租赁单 Mapper 测试替身，用于档期占用过滤。 */
        // 租赁查询替身：用于构造档期占用与空闲两种状态。
        // 默认返回空列表，表示所有工具档期都空闲。
    private RentalMapper rentalMapper;
    /** 推荐历史 Mapper 测试替身。 */
        // 推荐历史替身：验证推荐 ID 顺序、降级标记与写失败。
        // setUp 中默认让 insert 回填 ID=100。
    private AiRecommendationMapper recommendationMapper;
    /** 评价统计 Mapper 测试替身。 */
        // 评价统计替身：为降级排序与提示词提供评分摘要。
        // 默认无评价，代表冷启动工具。
    private ToolReviewMapper reviewMapper;
    /** 向量库提供者，可注入 null 模拟向量能力不可用。 */
        // 向量能力替身：注入 null 可直接走关键词兜底。
        // 需要召回时通过 vectorReturns 替换。
    private ObjectProvider<VectorStore> vectorProvider;
    /** 大模型客户端提供者，可注入 null 模拟 AI 不可用。 */
        // 大模型能力替身：注入 null 可跳过重排。
        // 需要重排时通过 chatReturns 替换。
    private ObjectProvider<ChatClient.Builder> chatProvider;

    /**
     * 单元测试没有 Spring 容器，提前注册工具与租赁实体的 MyBatis-Plus 表信息，
     * <p>
     * 单测不启动 Spring，MyBatis-Plus 不会自动注册表信息。
     * 没有表信息时 LambdaQueryWrapper 解析列名会抛异常。
     * 因此提前注册 Tool 与 Rental 两个实体的 TableInfo。
     * 这一步只做元数据初始化，不连接数据库。
     * 注册一次即可被所有用例复用，因此放在 @BeforeAll。
     * 新增使用 Lambda 查询的实体时，需要在这里补充注册。
     * 注册顺序不影响结果，实体之间没有依赖。
     * 该初始化是纯本地操作，不会产生 IO 或副作用。
     * 使 LambdaQueryWrapper 能解析列名，避免构造查询时抛“找不到表信息”。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Tool.class);
        TableInfoHelper.initTableInfo(assistant, Rental.class);
    }

    /**
     * 为每个用例创建全新的 Mapper、向量库与 ChatClient 测试替身。
     * 默认让评价统计为空、活动租赁为空，并把推荐历史插入回填固定 ID，
     * <p>
     * 每个用例使用全新 mock，避免 stub 在用例之间泄漏。
     * 默认评价统计为空，表示所有工具都无评分。
     * 默认活动租赁为空，表示所有工具档期都空闲。
     * 默认推荐历史插入回填固定 ID=100，便于断言。
     * 向量与 ChatClient 默认不可用，强制走降级路径。
     * 需要 AI 能力的用例再通过 vectorReturns/chatReturns 覆盖。
     * 这些默认值让用例只关注自身想验证的边界。
     * 回填 ID 使用 thenAnswer，模拟数据库自增主键。
     * mock 都是局部字段，不存在跨用例共享。
     * 该 setUp 不抛受检异常，保持测试方法简洁。
     * 使各用例只关注自身想验证的边界。
     */
    @BeforeEach
    void setUp() {
        toolMapper = mock(ToolMapper.class);
        rentalMapper = mock(RentalMapper.class);
        recommendationMapper = mock(AiRecommendationMapper.class);
        reviewMapper = mock(ToolReviewMapper.class);
        when(reviewMapper.selectStatsByToolIds(any())).thenReturn(List.of());
        when(rentalMapper.selectList(any())).thenReturn(List.of());
        when(recommendationMapper.insert(any(AiRecommendation.class))).thenAnswer(invocation -> {
            AiRecommendation record = invocation.getArgument(0);
            record.setId(100L);
            return 1;
        });
        vectorProvider = provider(null);
        chatProvider = provider(null);
    }

    /**
     * 辅助方法：在 Sa-Token Mock 上下文中以固定用户身份调用推荐服务。
     * <p>
     * 每次调用都重新建立 Mock 上下文，避免测试之间共享登录态。
     * 固定用户 ID 让归属校验的预期结果可预测。
     * 返回推荐结果，调用方可在上下文结束后继续断言。
     * 上下文只在本次调用期间有效，避免测试之间共享登录态。
     */
    private static ToolRecommendVO recommend(ToolRecommendService service, ToolRecommendDTO dto) {
        return SaTokenContextMockUtil.setMockContext(() -> {
            StpKit.USER.login(USER_ID);
            return service.recommend(dto);
        });
    }

        // 与 recommend 类似，但不关心返回值。
        // 用于 click/feedback 这类只产生副作用的操作。
        // 上下文同样只在本次调用期间有效。
    /** 辅助方法：在固定用户身份的 Mock 上下文中执行一段操作。 */
    private static void asUser(Runnable action) {
        SaTokenContextMockUtil.setMockContext(() -> {
            StpKit.USER.login(USER_ID);
            action.run();
        });
    }

    /**
     * 辅助方法：构造推荐服务，并按需通过反射覆盖 topK 与 maxResults 配置。
     * <p>
     * 通过反射覆盖私有配置字段，无需为测试新增构造参数。
     * topK 控制向量召回上限，maxResults 控制最终返回数量。
     * 传 null 表示保留生产默认值，避免测试耦合具体配置。
     * 未传入的配置保持生产默认值。
     */
    private ToolRecommendService service(Integer topK, Integer maxResults) {
        ToolRecommendService service = new ToolRecommendService(toolMapper, rentalMapper,
                recommendationMapper, reviewMapper, vectorProvider, chatProvider, new ObjectMapper());
        if (topK != null) {
            ReflectionTestUtils.setField(service, "topK", topK);
        }
        if (maxResults != null) {
            ReflectionTestUtils.setField(service, "maxResults", maxResults);
        }
        return service;
    }

        // Spring 的可选注入用 ObjectProvider 表达，服务只调用 getIfAvailable。
        // 传 null 即模拟依赖不可用，触发服务的降级分支。
        // 该方法只 mock 一个入口，保持测试替身最小。
    /** 辅助方法：模拟 ObjectProvider，使 getIfAvailable 返回指定依赖或 null。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /**
     * 辅助方法：构造一个已审核、可借用的工具，填充推荐过滤所需字段。
     * <p>
     * 默认审核通过、状态可用、租金为零，满足推荐基础条件。
     * 使用说明为富文本，验证摘要截断路径。
     * 调用方只需覆盖想构造边界的字段。
     * 调用方可覆盖状态、分类、押金等以构造边界。
     */
    private static Tool tool(long id, String category, String deposit, String status) {
        Tool tool = new Tool();
        tool.setId(id);
        tool.setName("工具" + id);
        tool.setCategory(category);
        tool.setModel("型号" + id);
        tool.setDeposit(new BigDecimal(deposit));
        tool.setStorageArea("A 区");
        tool.setStatus(status);
        tool.setAuditStatus(1);
        tool.setRentalCount(0);
        tool.setUsageGuide("<p>先断电再操作</p>");
        return tool;
    }

        // 只设置 question，其余过滤条件保持为空。
        // 这样默认不做分类、押金与档期过滤。
        // 需要过滤边界的用例再显式设置对应字段。
    /** 辅助方法：构造只带问题的推荐请求 DTO。 */
    private static ToolRecommendDTO query(String question) {
        ToolRecommendDTO dto = new ToolRecommendDTO();
        dto.setQuestion(question);
        return dto;
    }

        // 评分使用 BigDecimal，避免 double 精度噪声。
        // reviewCount 帮助模型判断评分可信度。
        // 该统计同时用于降级排序与重排提示词。
    /** 辅助方法：构造指定工具的平均分与评价数统计。 */
    private static ToolRatingStats stats(long toolId, String ratingAvg, long reviewCount) {
        ToolRatingStats stats = new ToolRatingStats();
        stats.setToolId(toolId);
        stats.setRatingAvg(new BigDecimal(ratingAvg));
        stats.setReviewCount(reviewCount);
        return stats;
    }

        // 文档正文固定，测试只关心元数据中的 toolId。
        // toolId 用于把向量命中映射回工具实体。
        // 文档 ID 唯一，避免多条命中相互覆盖。
    /** 辅助方法：构造带 toolId 元数据的向量文档，模拟向量库召回结果。 */
    private static Document doc(long toolId) {
        return new Document("tool-" + toolId, "工具文本", Map.of("toolId", toolId));
    }

    /**
     * 辅助方法：把向量提供者替换为指定 store，并让相似度检索返回给定文档。
     * <p>
     * 同时替换 provider 与 similaritySearch 返回值，避免分两步配置。
     * 只影响当前用例，构造函数会在下次 setUp 重置。
     * 文档列表的顺序即召回顺序，可作为降级排序的基线。
     * 用于快速布置召回阶段的数据。
     */
    private void vectorReturns(VectorStore store, List<Document> docs) {
        vectorProvider = provider(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
    }

    /**
     * 辅助方法：构造完整的 ChatClient 调用链，使 prompt/system/user/call 返回指定内容。
     * <p>
     * 逐层 mock ChatClient.Builder → ChatClient → prompt → call 调用链。
     * content 参数既可以是合法 JSON，也可以是非法文本。
     * 需要模拟异常时，调用方可在返回的 spec 上重新 stub call()。
     * 用于验证重排成功、非法 JSON 与异常降级等分支。
     */
    private ChatClientRequestSpec chatReturns(String content) {
        ChatClientRequestSpec spec = mock(ChatClientRequestSpec.class);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        CallResponseSpec response = mock(CallResponseSpec.class);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn(content);
        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(spec);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        chatProvider = provider(builder);
        return spec;
    }

    /**
     * 场景：向量库与关键词查询都没有候选工具。
     * 前置条件：工具查询返回空列表，向量提供者为空。
     * 断言目标：返回空 items、标记降级，并仍生成推荐历史 ID；
     * 该用例是推荐链路最基础的冒烟场景，不依赖任何外部 AI 能力。
     * 空候选也必须留下推荐历史，便于统计“无结果”请求占比。
     * 降级标记用于区分“模型未参与”和“模型参与但无结果”。
     * 返回结构保持 items/summary/recommendationId 三字段稳定。
     * 即使后续新增过滤条件，也不能让空候选路径抛异常。
     * 该用例同时保护关键词兜底入口的空输入分支。
     * 若这里失败，优先检查兜底查询是否在无词时误扫全表。
     * 断言不关注 summary 文案，避免绑定具体提示语。
     * 即使没有结果也不抛异常，保证前端能拿到稳定结构。
     */
    @Test
    void no_candidates_returns_empty_result_without_throwing() {
        // 工具查询返回空列表，模拟知识库与向量库都没有候选。
        // 向量提供者为 null，服务应直接走关键词兜底而不是报错。
        // 推荐入口仍要生成历史记录，哪怕没有任何工具可推。
        // items 为空是正常业务结果，不是异常。
        // degraded=true 表示本次结果未经过大模型重排。
        // recommendationId 非空证明历史落库成功，可供后续点击归因。
        // 三条断言共同定义“空结果”的稳定响应结构。
        // 该路径不抛异常，前端无需为空结果单独写错误处理。
        // 后续用例会在此基线上加入关键词、过滤与重排分支。
        when(toolMapper.selectList(any())).thenReturn(List.of());

        ToolRecommendVO vo = recommend(service(null, null), query("a"));

        assertTrue(vo.getItems().isEmpty());
        assertTrue(vo.isDegraded(), "没有向量结果时走关键词兜底，应标记降级");
        assertNotNull(vo.getRecommendationId(), "无候选也要留下推荐记录");
    }

    /**
     * 场景：用户问题无法提取有效关键词，向量库也不可用。
     * 前置条件：兜底查询若没有可匹配词，不应退化为全表扫描。
     * 断言目标：items 为空且工具 Mapper 的 selectList 从未被调用，
     * 该用例针对“无效输入”而非“无数据”，区别在于是否触发查询。
     * 问题「a」无法提取有效关键词，兜底查询应直接返回空。
     * SQL 层不执行 selectList，数据库压力与输入长度无关。
     * verify(never) 是本用例的核心断言，比结果为空更严格。
     * 若未来加入模糊匹配，也必须保留无词短路。
     * 该保护同时降低恶意刷接口时的数据库负载。
     * 测试不关心返回的 summary 文案，只关心是否访问数据库。
     * 工具 Mapper 虽配置了返回值，但不应被真正调用。
     * 防止恶意或无意义输入拖垮数据库。
     */
    @Test
    void keyword_fallback_without_terms_never_scans_whole_table() {
        // 即使 Mapper 配置了候选，服务也不应该调用它。
        // 这个预置返回值用于证明“没调用”，而不是“调用后为空”。
        // 问题只有单个字符，关键词提取结果为空。
        // 无词时应直接短路，避免退化为全表扫描。
        // items 为空是预期结果。
        // never() 断言确保 selectList 一次都没有执行。
        // 若实现先查全表再在内存过滤，这里会失败。
        // 该用例把性能保护写成可执行契约。
        // 对恶意输入，短路比任何索引优化都更有效。
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1, "WX", "100", "AVAILABLE")));

        ToolRecommendVO vo = recommend(service(null, null), query("a"));

        assertTrue(vo.getItems().isEmpty());
        verify(toolMapper, never()).selectList(any());
    }

    /**
     * 场景：关键词兜底命中 30 个工具，配置最多返回 5 条。
     * 前置条件：服务端同时限制 SQL 返回量和最终结果量。
     * 断言目标：结果恰为 5 条；捕获的查询条件 SQL 中必须包含 LIMIT，
     * 该用例同时验证 SQL 层与结果层的双重上限。
     * 30 个候选模拟关键词命中较多的情况。
     * maxResults=5 是最终返回上限，LIMIT 是数据库读取上限。
     * 两者都必要：只截结果仍会把整表读进内存。
     * 捕获 Wrapper 的 SQL 片段，直接检查是否包含 LIMIT。
     * 断言失败信息附带实际 SQL，便于快速定位实现问题。
     * 该用例不依赖向量库或大模型，结果完全由兜底查询决定。
     * 上限值来自可配置字段，测试通过构造参数覆盖。
     * 证明兜底不会把整表加载进内存再截断。
     */
    @Test
    void keyword_fallback_is_bounded_by_sql_limit_and_result_limit() {
        // 构造 30 个可用工具，数量远大于最终返回上限。
        // 全部工具都满足分类、押金与状态条件，确保过滤不是变量。
        // Mapper 返回完整列表，服务必须在 SQL 与结果两层截断。
        // maxResults=5 通过 service(null, 5) 注入。
        // 最终只返回 5 条，验证结果层上限。
        // 捕获实际执行的查询条件，检查 SQL 片段。
        // LIMIT 必须出现在 SQL 中，而不是仅在 Java 内存里截断。
        // 失败时输出实际 SQL，方便定位是哪个查询缺少限制。
        // 该断言把“不扫全表”的性能要求固化下来。
        List<Tool> many = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            many.add(tool(i, "WX", "100", "AVAILABLE"));
        }
        when(toolMapper.selectList(any())).thenReturn(many);

        ToolRecommendVO vo = recommend(service(null, 5), query("打孔"));

        assertEquals(5, vo.getItems().size());
        ArgumentCaptor<Wrapper<Tool>> captor = wrapperCaptor();
        verify(toolMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("LIMIT"),
                "关键词兜底必须带 LIMIT，实际 SQL: " + captor.getValue().getSqlSegment());
    }

    /**
     * 场景：兜底候选的使用说明长达 5000 字。
     * 前置条件：返回前端或提示词的摘要字段需要限额。
     * 使用说明可能来自富文本，长度不可控。
     * 兜底结果既要返回前端，也可能拼进提示词。
     * 因此摘要字段必须有硬截断，避免响应体与 token 膨胀。
     * 该用例只验证截断上限，不绑定具体截断算法。
     * 300 字是展示与成本的折中值。
     * 截断发生在映射/组装阶段，而不是数据库查询阶段。
     * 若未来改为按字节截断，仍需满足长度上限。
     * 该用例与重排 prompt 上限用例互为补充。
     * 断言目标：使用说明被截断到 300 字以内，避免响应体和提示词膨胀。
     */
    @Test
    void keyword_fallback_truncates_summary_field() {
        // 构造一个使用说明长达 5000 字的工具。
        // 这种长度在真实富文本中并不罕见。
        // 兜底查询返回该工具，触发摘要组装逻辑。
        // 只调用推荐入口，不关心排序结果。
        // 组装后的 usageGuide 必须被截断到 300 字以内。
        // 断言使用 <= 而不是 ==，允许实现选择更短的上限。
        // 失败信息说明截断缺失会导致响应体膨胀。
        // 该字段同时可能进入提示词，因此截断也是成本保护。
        // 测试直接检查实体字段，说明截断发生在返回前。
        Tool longGuide = tool(1, "WX", "100", "AVAILABLE");
        longGuide.setUsageGuide("x".repeat(5000));
        when(toolMapper.selectList(any())).thenReturn(List.of(longGuide));

        recommend(service(null, null), query("打孔"));

        assertTrue(longGuide.getUsageGuide().length() <= 300,
                "关键词兜底返回的使用说明摘要应截断");
    }

    /**
     * 场景：配置 topK=1000 请求向量召回。
     * 前置条件：服务端对召回数量设置硬上限。
     * 断言目标：实际传给向量库的 topK 不超过 50，
     * topK 可能来自配置或请求参数，不能直接透传给向量库。
     * 该用例把配置设为 1000，验证服务端硬上限。
     * 实际 topK 必须 <= 50，防止检索成本被放大。
     * 捕获传给 similaritySearch 的 SearchRequest 进行检查。
     * 即使工具查询为空，召回仍应先受上限约束。
     * 该保护对恶意请求与错误配置同样有效。
     * 断言失败信息输出实际 topK，便于定位配置来源。
     * 上限值是服务端不变量，不应由调用方覆盖。
     * 防止错误配置或恶意参数放大检索成本。
     */
    @Test
    void vector_recall_has_a_hard_upper_limit() {
        // 向量库返回空结果，聚焦 topK 参数本身。
        // 直接替换 vectorProvider，模拟可用的向量能力。
        // 工具查询返回空，避免候选过滤干扰观察。
        // 以 topK=1000 调用服务，触发上限逻辑。
        // 捕获实际传给向量库的检索请求。
        // 无论配置多大，最终 topK 都不能超过 50。
        // 失败时打印实际值，便于判断是配置还是实现问题。
        // 该断言保护的是成本与延迟，而不是结果正确性。
        // 上限检查应发生在调用向量库之前。
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        vectorProvider = provider(store);
        when(toolMapper.selectList(any())).thenReturn(List.of());

        recommend(service(1000, null), query("打孔"));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        assertTrue(captor.getValue().getTopK() <= 50,
                "向量召回 topK 必须被硬上限截断，实际 " + captor.getValue().getTopK());
    }

    /**
     * 场景：向量返回 5 个候选，覆盖分类不符、押金超限、状态不可用与档期冲突。
     * 前置条件：用户指定分类 WX、押金上限 200、未来档期。
     * 断言目标：最终只剩工具 1：分类匹配、押金合规、状态可用且档期空闲；
     * 该用例是推荐过滤规则的集中验证。
     * 五个候选分别覆盖分类不符、押金超限、状态不可用与档期冲突。
     * 只有工具 1 同时满足分类 WX、押金 <= 200、可用且档期空闲。
     * 过滤顺序不影响最终结果，但每一项都必须生效。
     * 租出中的工具即使分类与押金都合规也应被排除。
     * 已有活动租赁的工具同样不能推荐，避免重复预约。
     * 断言最终只剩一条，能同时发现“漏过滤”和“误过滤”。
     * 该用例不依赖大模型，向量召回后直接进入规则过滤。
     * 租出中与已有活动租赁的工具都被过滤。
     */
    @Test
    void only_available_tools_matching_category_deposit_and_time_are_recommended() {
        // 向量召回 5 个候选，顺序不代表最终推荐顺序。
        // 工具 1 合规；2 分类不符；3 押金超限；4 档期冲突；5 已租出。
        // 工具 4 存在活动租赁，模拟同一档期已被占用。
        // 请求显式指定分类、押金上限与未来档期。
        // 分类过滤只保留 WX 工具。
        // 押金过滤排除 500 元的工具 3。
        // 状态过滤排除 RENTED 的工具 5。
        // 档期过滤排除已有租赁的工具 4。
        // 最终只剩工具 1，证明四类过滤条件全部生效。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1), doc(2), doc(3), doc(4), doc(5)));
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                tool(1, "WX", "100", "AVAILABLE"),
                tool(2, "YY", "50", "AVAILABLE"),
                tool(3, "WX", "500", "AVAILABLE"),
                tool(4, "WX", "100", "AVAILABLE"),
                tool(5, "WX", "100", "RENTED")));
        Rental busy = new Rental();
        busy.setToolId(4L);
        when(rentalMapper.selectList(any())).thenReturn(List.of(busy));

        ToolRecommendDTO dto = query("打孔");
        dto.setCategory("WX");
        dto.setMaxDeposit(new BigDecimal("200"));
        dto.setStartTime(LocalDateTime.now().plusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(2));

        ToolRecommendVO vo = recommend(service(null, null), dto);

        assertEquals(1, vo.getItems().size());
        assertEquals(1L, vo.getItems().getFirst().getToolId());
    }

    /**
     * 场景：大模型返回合法 JSON，把工具 2 排在工具 1 之前并给出理由。
     * 前置条件：向量召回两个可用工具，ChatClient 返回结构化重排结果。
     * 断言目标：响应不标记降级、摘要与顺序采用模型结果、每条带理由；
     * 该用例验证大模型重排的成功分支。
     * 模型返回 summary 与 items，items 顺序为 2、1。
     * 服务必须采用模型顺序，而不是恢复向量召回顺序。
     * 每个 item 的 reason 来自模型，不能为空。
     * degraded=false 表示模型参与且结果可信。
     * 历史记录保存推荐 ID 顺序“2,1”，用于点击归因。
     * degraded=0 与响应标记保持一致，便于监控。
     * 该用例同时保护结构化 JSON 解析与落库字段。
     * 历史记录保存推荐 ID 顺序“2,1”且 degraded=0，便于后续点击归因。
     */
    @Test
    void rerank_returns_reasons_and_keeps_model_order_in_history() {
        // 向量召回两个都合规的工具，排除过滤变量。
        // 模型返回合法 JSON，把工具 2 排在工具 1 之前。
        // 调用推荐入口，触发解析、排序与历史落库。
        // 模型正常参与时不标记降级。
        // summary 直接采用模型输出。
        // 最终顺序必须是 2、1，而不是召回顺序 1、2。
        // 每条推荐都携带模型给出的理由。
        // 捕获历史记录，验证推荐 ID 顺序被持久化。
        // degraded=0 与响应中的 false 一致，便于统计模型成功率。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1), doc(2)));
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                tool(1, "WX", "100", "AVAILABLE"),
                tool(2, "WX", "100", "AVAILABLE")));
        chatReturns("{\"summary\":\"用这款\",\"items\":["
                + "{\"toolId\":2,\"reason\":\"适合打孔\"},{\"toolId\":1,\"reason\":\"轻便\"}]}");

        ToolRecommendVO vo = recommend(service(null, null), query("打孔"));

        assertFalse(vo.isDegraded());
        assertEquals("用这款", vo.getSummary());
        assertEquals(List.of(2L, 1L), vo.getItems().stream().map(ToolRecommendVO.Item::getToolId).toList());
        assertEquals("适合打孔", vo.getItems().getFirst().getReason());
        assertEquals("轻便", vo.getItems().get(1).getReason());

        ArgumentCaptor<AiRecommendation> captor = ArgumentCaptor.forClass(AiRecommendation.class);
        verify(recommendationMapper).insert(captor.capture());
        assertEquals("2,1", captor.getValue().getRecommendedIds());
        assertEquals(0, captor.getValue().getDegraded());
    }

    /**
     * 场景：大模型返回非 JSON 文本。
     * 前置条件：重排结果无法解析。
     * 断言目标：自动降级为启发式顺序、标记 degraded，但仍为每个工具生成非空理由；
     * 模型可能返回自然语言而非约定 JSON。
     * 服务必须把解析失败视为可恢复错误，而不是整体失败。
     * 降级后按启发式顺序返回候选，通常保持召回顺序。
     * 每条推荐仍要生成非空理由，不能留空字段。
     * degraded=true 告诉前端本次结果未经模型重排。
     * 历史记录 degraded=1，便于监控模型输出质量。
     * 该用例与合法 JSON 用例构成重排的成败对照。
     * 断言同时覆盖响应结构与落库标记。
     * 历史记录标记 degraded=1，便于监控模型可用性。
     */
    @Test
    void invalid_rerank_output_falls_back_to_heuristic_order_and_marks_degraded() {
        // 两个可用工具，确保失败原因只能是模型输出格式。
        // 模型返回纯文本“这不是 JSON”，触发解析异常分支。
        // 服务应捕获异常并改用启发式排序。
        // 降级标记必须为 true。
        // 启发式顺序保持候选原始顺序 1、2。
        // 即使降级，每条推荐也要有非空理由。
        // 捕获落库的历史记录。
        // degraded=1 与响应标记一致，便于统计降级率。
        // 该用例证明模型格式错误不会让推荐接口失败。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1), doc(2)));
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                tool(1, "WX", "100", "AVAILABLE"),
                tool(2, "WX", "100", "AVAILABLE")));
        chatReturns("这不是 JSON");

        ToolRecommendVO vo = recommend(service(null, null), query("打孔"));

        assertTrue(vo.isDegraded());
        assertEquals(List.of(1L, 2L), vo.getItems().stream().map(ToolRecommendVO.Item::getToolId).toList());
        assertTrue(vo.getItems().stream().allMatch(item -> item.getReason() != null && !item.getReason().isBlank()),
                "降级后仍要给出推荐理由");

        ArgumentCaptor<AiRecommendation> captor = ArgumentCaptor.forClass(AiRecommendation.class);
        verify(recommendationMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getDegraded());
    }

    /**
     * 场景：重排失败，候选工具评分分别为 2.0、5.0 与无评价。
     * 前置条件：降级排序按评分倒序，但不得丢弃无评价工具。
     * 降级排序的核心目标是仍给用户合理的结果。
     * 评分高的工具优先，但无评价工具不能被丢弃。
     * 冷启动工具需要曝光机会，因此保留在列表末尾。
     * 排序依据来自 reviewMapper 的统计结果。
     * 该用例中工具 2 为 5 分、工具 1 为 2 分、工具 3 无评价。
     * 期望顺序为 2、1、3。
     * 模型输出非法 JSON 以强制走启发式分支。
     * 断言同时验证排序与“无评价保留”两个不变量。
     * 断言目标：顺序为 5 分、2 分、无评价，保证冷启动工具仍有曝光机会。
     */
    @Test
    void degraded_heuristic_orders_by_rating_and_keeps_unrated_tools() {
        // 三个工具都满足基础过滤条件，差异只在评分。
        // 为工具 1、2 提供评分统计，工具 3 没有评价。
        // 模型返回非法 JSON，强制进入降级排序。
        // 降级标记为 true。
        // 评分 5.0 的工具 2 排第一。
        // 评分 2.0 的工具 1 排第二。
        // 无评价的工具 3 仍保留在第三位。
        // 失败信息说明降级排序必须按评分倒序且不丢弃冷启动工具。
        // 该顺序策略避免“无评价即无曝光”的马太效应。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1), doc(2), doc(3)));
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                tool(1, "WX", "100", "AVAILABLE"),
                tool(2, "WX", "100", "AVAILABLE"),
                tool(3, "WX", "100", "AVAILABLE")));
        when(reviewMapper.selectStatsByToolIds(any())).thenReturn(List.of(
                stats(1, "2.0", 1), stats(2, "5.0", 2)));
        chatReturns("这不是 JSON");

        ToolRecommendVO vo = recommend(service(null, 5), query("打孔"));

        assertTrue(vo.isDegraded());
        assertEquals(List.of(2L, 1L, 3L),
                vo.getItems().stream().map(ToolRecommendVO.Item::getToolId).toList(),
                "降级排序应按评分倒序，无评价工具保留在结果中");
    }

    /**
     * 场景：调用大模型时抛出运行时异常。
     * 前置条件：ChatClient 的 call 被 mock 为抛异常。
     * 断言目标：异常被服务内部捕获并降级，调用方仍得到一条可用推荐，
     * 与非法 JSON 不同，这里模拟调用本身抛异常。
     * 异常可能来自网络、鉴权或模型服务内部错误。
     * 服务必须在内部捕获，不能把异常抛给控制器。
     * 降级后仍返回一条可用推荐。
     * degraded=true 表示本次结果未经过模型重排。
     * 该用例保护 AI 故障时的主流程可用性。
     * 捕获范围应覆盖 call() 及其下游整条链。
     * 断言只关注“不抛异常 + 有结果”，不绑定理由文案。
     * 外部 AI 故障不影响主流程。
     */
    @Test
    void rerank_exception_falls_back_and_does_not_reach_caller() {
        // 向量召回一个可用工具，确保过滤后仍有候选。
        // 逐层 mock 调用链，并在 call() 处抛异常。
        // 这与返回非法内容不同，异常发生在调用阶段。
        // 服务应捕获异常并降级，而不是继续向上抛。
        // 调用方正常拿到推荐结果。
        // 结果被标记为降级。
        // 至少保留一条可用推荐，不能让 AI 故障清空列表。
        // 该用例是“外部依赖异常隔离”原则的直接体现。
        // 若这里抛异常，控制器会返回 500，用户无法获得任何结果。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1)));
        when(toolMapper.selectBatchIds(any(Collection.class)))
                .thenReturn(List.of(tool(1, "WX", "100", "AVAILABLE")));
        ChatClientRequestSpec spec = mock(ChatClientRequestSpec.class);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenThrow(new IllegalStateException("llm down"));
        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(spec);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        chatProvider = provider(builder);

        ToolRecommendVO vo = recommend(service(null, null), query("打孔"));

        assertTrue(vo.isDegraded());
        assertEquals(1, vo.getItems().size());
    }

    /**
     * 场景：30 个候选都带超长使用说明，用户问题也超长。
     * 前置条件：提示词需要控制 token 成本与注入面。
     * 断言目标：user 提示词长度不超过 7000 字，且不包含完整长说明，
     * 提示词会直接产生 token 成本，也可能被长文本注入。
     * 该用例构造 30 个候选，每个使用说明长达数千字。
     * 用户问题也被重复放大到 1000 字。
     * 服务必须只把截断后的摘要字段放进 prompt。
     * user 提示词总长度不得超过 7000 字。
     * 完整长说明不能出现在 prompt 中。
     * 捕获 user() 实参进行检查，而不是猜测内部拼接。
     * 该用例同时保护成本、延迟与提示词注入面。
     * 只能携带截断后的摘要字段。
     */
    @Test
    void rerank_prompt_only_carries_bounded_summary_fields() {
        // 构造 30 个候选文档与工具，模拟召回较多的情况。
        // 每个工具的使用说明重复 800 次，远超声明的 300 字上限。
        // 向量库返回全部候选，工具查询也返回全部。
        // 模型返回空结果，重点不在重排质量而在 prompt 内容。
        // 用户问题重复 250 次，验证长输入不会线性放大 prompt。
        // 捕获最终传给模型 user() 的字符串。
        // 总长度必须受 7000 字硬上限约束。
        // 完整长说明绝不能进入 prompt，只能携带截断摘要。
        // 该断言同时降低 token 成本与被提示词注入的风险。
        VectorStore store = mock(VectorStore.class);
        List<Document> docs = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            docs.add(doc(i));
            Tool tool = tool(i, "WX", "100", "AVAILABLE");
            tool.setUsageGuide("使用说明".repeat(800));
            tools.add(tool);
        }
        vectorReturns(store, docs);
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(tools);
        ChatClientRequestSpec spec = chatReturns("{\"summary\":\"\",\"items\":[]}");

        recommend(service(null, null), query("打孔".repeat(250)));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(spec).user(prompt.capture());
        assertTrue(prompt.getValue().length() <= 7000,
                "重排 prompt 必须带上限，实际 " + prompt.getValue().length());
        assertFalse(prompt.getValue().contains("使用说明".repeat(800)),
                "prompt 只能携带截断后的摘要字段");
    }

    /**
     * 场景：候选中包含 4.5 分/3 条与 2.0 分/1 条评价的工具。
     * 前置条件：重排提示词应携带评分摘要辅助模型判断。
     * 断言目标：提示词中出现 ratingAvg=4.5 与 reviewCount=3，
     * 重排质量依赖候选的质量信息。
     * 该用例为两个工具提供不同评分与评价数。
     * prompt 中必须出现 ratingAvg 与 reviewCount。
     * 字段名使用 JSON 键形式，便于模型结构化理解。
     * 评分保留一位小数，避免精度噪声。
     * 评价数量帮助模型判断评分的可信度。
     * 该用例捕获 user() 实参，直接检查序列化结果。
     * 它保证“评分摘要”没有在模板重构中被悄悄移除。
     * 让模型在质量维度上有可依据的信息。
     */
    @Test
    void rerank_candidates_carry_rating_summary() {
        // 两个候选都可用，差异只在评分统计。
        // 工具 1：4.5 分、3 条评价；工具 2：2.0 分、1 条评价。
        // 模型返回空结果，聚焦 prompt 内容。
        // 捕获 user() 实参。
        // 平均分必须以 4.5 的形式出现，而不是四舍五入成 5。
        // 评价数量也必须出现，帮助模型判断样本量。
        // 字段名使用 JSON 键，保持结构化提示风格。
        // 失败信息打印完整 prompt，便于排查模板变更。
        // 该用例与 prompt 长度上限用例共同定义重排输入契约。
        VectorStore store = mock(VectorStore.class);
        vectorReturns(store, List.of(doc(1), doc(2)));
        when(toolMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                tool(1, "WX", "100", "AVAILABLE"),
                tool(2, "WX", "100", "AVAILABLE")));
        when(reviewMapper.selectStatsByToolIds(any())).thenReturn(List.of(
                stats(1, "4.5", 3), stats(2, "2.0", 1)));
        ChatClientRequestSpec spec = chatReturns("{\"summary\":\"\",\"items\":[]}");

        recommend(service(null, null), query("打孔"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(spec).user(prompt.capture());
        assertTrue(prompt.getValue().contains("\"ratingAvg\":4.5"),
                "重排候选必须携带平均分: " + prompt.getValue());
        assertTrue(prompt.getValue().contains("\"reviewCount\":3"),
                "重排候选必须携带评价数量: " + prompt.getValue());
    }

    /**
     * 场景：当前用户尝试点击或反馈他人的推荐记录。
     * 前置条件：推荐记录 userId 与登录用户不一致。
     * 推荐记录带有 userId，点击与反馈都必须校验归属。
     * 该用例构造一条属于其他用户的记录。
     * 当前登录用户固定为 USER_ID。
     * click 与 feedback 都应抛出 BizException。
     * 越权写入会污染他人的推荐效果数据。
     * 校验必须发生在更新数据库之前。
     * 两种操作分别断言，避免只保护其中一条路径。
     * 异常类型使用业务异常，便于全局映射为 403。
     * 断言目标：两种操作都抛出业务异常，防止越权改写他人推荐行为数据。
     */
    @Test
    void click_and_feedback_only_operate_on_own_records() {
        // 记录归属 USER_ID+1，与当前登录用户不一致。
        // Mapper 按 ID 返回该记录，模拟攻击者猜到记录 ID。
        // 在 Mock 登录上下文中调用 click，应被归属校验拒绝。
        // feedback 同样必须拒绝，不能只保护点击路径。
        // 两个操作都抛出 BizException，而不是静默失败。
        // 业务异常会被全局处理器映射为权限类错误。
        // 该用例不验证数据库未被更新，由下一个用例补充。
        // 归属校验是推荐效果统计可信的前提。
        // 若缺少校验，攻击者可批量伪造他人的点击与反馈。
        AiRecommendation other = record(USER_ID + 1);
        when(recommendationMapper.selectById(9L)).thenReturn(other);

        ToolRecommendService service = service(null, null);
        assertThrows(BizException.class, () -> asUser(() -> service.click(9L, 1L)));
        assertThrows(BizException.class, () -> asUser(() -> service.feedback(9L, 1)));
    }

    /**
     * 场景：点击的工具有效，但不在该推荐记录的候选列表中。
     * 前置条件：记录的 recommendedIds 为“1,2”，点击工具 99。
     * 记录归属正确不代表点击工具合法。
     * 点击工具必须出现在该记录的 recommendedIds 中。
     * 该记录推荐“1,2”，点击工具 99 应被拒绝。
     * 校验失败时不能更新推荐记录。
     * verify(never).updateById 确认没有写入副作用。
     * 该规则防止通过猜测 toolId 伪造点击归因。
     * 点击归因直接影响推荐排序与运营统计。
     * 异常使用 BizException，保持与归属校验一致的错误语义。
     * 断言目标：抛出业务异常且不更新记录，保证点击归因真实可信。
     */
    @Test
    void click_must_reference_a_tool_from_this_recommendation() {
        // 记录属于当前用户，排除归属失败这一干扰因素。
        // recommendedIds 为“1,2”，工具 99 不在其中。
        // 点击不存在的工具应抛出业务异常。
        // 同时确认没有调用 updateById，避免部分更新。
        // 校验必须发生在写库之前，而不是写后再回滚。
        // 该用例保护点击数据的真实性。
        // 若只校验记录归属，攻击者仍可伪造任意工具点击。
        // 失败时全局异常会映射为业务错误，而不是 500。
        // 该规则也让点击统计能准确归因到具体推荐位。
        when(recommendationMapper.selectById(9L)).thenReturn(record(USER_ID));

        assertThrows(BizException.class, () -> asUser(() -> service(null, null).click(9L, 99L)));
        verify(recommendationMapper, never()).updateById(any(AiRecommendation.class));
    }

    /**
     * 场景：本人推荐记录依次被点击与反馈。
     * 前置条件：记录归属当前用户且推荐 ID 包含被点击工具。
     * 断言目标：两次 updateById 分别写入 clickedToolId=2 与 feedback=1，
     * 正常路径：本人记录、合法工具、合法反馈值。
     * click 写入 clickedToolId=2。
     * feedback 写入 feedback=1。
     * 两次操作都通过 updateById 持久化。
     * 使用 ArgumentCaptor 捕获两次更新参数。
     * 第一次更新对应点击，第二次对应反馈。
     * 断言顺序依赖调用顺序，测试中显式串行执行。
     * 该用例与越权用例构成正反两面。
     * 形成可统计的推荐效果数据。
     */
    @Test
    void click_and_feedback_update_own_record() {
        // 记录归属当前用户，recommendedIds 包含工具 2。
        // 先点击工具 2，再提交“有用”反馈。
        // 两次操作都在 Mock 登录上下文中执行。
        // 捕获所有 updateById 调用的参数。
        // 预期恰好两次更新，分别对应点击与反馈。
        // 第一次更新的 clickedToolId 应为 2。
        // 第二次更新的 feedback 应为 1。
        // 两个字段分别落库，便于后续统计转化率。
        // 该用例确认正常路径不会触发归属或工具校验误报。
        when(recommendationMapper.selectById(9L)).thenReturn(record(USER_ID));
        ToolRecommendService service = service(null, null);

        asUser(() -> service.click(9L, 2L));
        asUser(() -> service.feedback(9L, 1));

        ArgumentCaptor<AiRecommendation> captor = ArgumentCaptor.forClass(AiRecommendation.class);
        verify(recommendationMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertEquals(2L, captor.getAllValues().get(0).getClickedToolId());
        assertEquals(1, captor.getAllValues().get(1).getFeedback());
    }

    /**
     * 场景：提交反馈值 3。
     * 前置条件：反馈只允许 1（有用）与 2（无用）。
     * 断言目标：参数在查询记录前就被拒绝，抛出业务异常且不访问数据库，
     * 反馈值只允许 1（有用）与 2（无用）。
     * 该用例提交非法值 3。
     * 校验必须发生在查询推荐记录之前。
     * 因此不仅要断言抛异常，还要断言没有 selectById。
     * verify(never) 证明无效值不会触达数据库。
     * 该保护避免脏数据进入效果统计。
     * 异常使用 BizException，保持统一错误语义。
     * 参数校验越早，越能减少无效 IO 与攻击面。
     * 避免无效值污染统计。
     */
    @Test
    void feedback_rejects_values_outside_one_and_two() {
        // 提交反馈值 3，超出允许范围。
        // 在 Mock 登录上下文中调用 feedback。
        // 非法值应直接抛出业务异常。
        // 关键断言：连推荐记录都没有查询。
        // 这说明参数校验发生在数据库访问之前。
        // 若先查记录再校验，会产生可被利用的无效查询。
        // 该用例保护统计数据的干净程度。
        // 与越权用例一样，业务异常会映射为客户端错误。
        // 校验顺序比异常类型更值得锁定。
        assertThrows(BizException.class, () -> asUser(() -> service(null, null).feedback(9L, 3)));
        verify(recommendationMapper, never()).selectById(any());
    }

    /**
     * 场景：推荐结果已生成，但写推荐历史时数据库异常。
     * 前置条件：recommendationMapper.insert 抛出运行时异常。
     * 断言目标：recommendationId 为 null，但推荐结果仍正常返回，
     * 推荐历史是辅助数据，不应影响主流程。
     * 该用例让 insert 抛出运行时异常。
     * 推荐结果仍必须正常返回，items 至少一条。
     * recommendationId 为 null，表示历史没有落库。
     * 前端应能容忍没有 recommendationId 的情况。
     * 该行为保证数据库抖动时用户仍能获得推荐。
     * 异常在服务内部捕获，不向调用方传播。
     * 与 AI 降级用例共同体现“辅助能力失败不阻断主流程”。
     * 历史记录失败不能影响用户获得推荐。
     */
    @Test
    void history_write_failure_does_not_break_recommendation() {
        // 让历史插入直接抛异常，模拟数据库写入失败。
        // 关键词兜底仍能返回一条可用工具。
        // 调用推荐入口，观察主流程是否被历史失败拖垮。
        // recommendationId 为 null，表示没有历史记录可归因。
        // 但推荐结果仍有一条，用户不会看到空列表。
        // 服务吞掉了历史写入异常，而不是向上传播。
        // 前端需要在没有 recommendationId 时禁用点击上报。
        // 该用例与重排异常用例共享同一降级哲学。
        // 辅助数据失败可以容忍，主流程必须保持可用。
        when(recommendationMapper.insert(any(AiRecommendation.class))).thenThrow(new IllegalStateException("db down"));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1, "WX", "100", "AVAILABLE")));

        ToolRecommendVO vo = recommend(service(null, null), query("打孔"));

        assertNull(vo.getRecommendationId());
        assertEquals(1, vo.getItems().size());
    }

        // userId 用于归属校验，推荐 ID 用于点击工具合法性校验。
        // 固定记录 ID=9，便于用例直接按 ID 查询。
        // recommendedIds “1,2” 与两个候选工具对应。
    /** 辅助方法：构造归属指定用户的推荐历史记录，推荐 ID 固定为“1,2”。 */
    private static AiRecommendation record(long userId) {
        AiRecommendation record = new AiRecommendation();
        record.setId(9L);
        record.setUserId(userId);
        record.setRecommendedIds("1,2");
        return record;
    }

    /**
     * <p>
     * Java 泛型在运行时擦除，无法直接 ArgumentCaptor.forClass(Wrapper<Tool>.class)。
     * 因此先按原始 Wrapper 捕获，再在断言处读取 SQL 片段。
     * 该技巧只用于测试，不影响生产代码。
     * 辅助方法：绕过泛型擦除捕获 Wrapper，以便检查兜底查询的真实 SQL 片段。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<Tool>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
