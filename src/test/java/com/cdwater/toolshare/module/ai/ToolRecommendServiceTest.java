package com.cdwater.toolshare.module.ai;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
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
 * 工具推荐闭环：用测试替身验证召回上限、关键词兜底边界、可用性过滤、
 * 重排理由与降级、点击/反馈归属，以及外部异常不抛给前端。
 */
class ToolRecommendServiceTest {

    private static final long USER_ID = 7L;

    private ToolMapper toolMapper;
    private RentalMapper rentalMapper;
    private AiRecommendationMapper recommendationMapper;
    private ObjectProvider<VectorStore> vectorProvider;
    private ObjectProvider<ChatClient.Builder> chatProvider;

    /** 单测没有 Spring 容器，手动初始化 MyBatis-Plus 的 Lambda 列缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Tool.class);
        TableInfoHelper.initTableInfo(assistant, Rental.class);
    }

    @BeforeEach
    void setUp() {
        toolMapper = mock(ToolMapper.class);
        rentalMapper = mock(RentalMapper.class);
        recommendationMapper = mock(AiRecommendationMapper.class);
        when(rentalMapper.selectList(any())).thenReturn(List.of());
        when(recommendationMapper.insert(any(AiRecommendation.class))).thenAnswer(invocation -> {
            AiRecommendation record = invocation.getArgument(0);
            record.setId(100L);
            return 1;
        });
        vectorProvider = provider(null);
        chatProvider = provider(null);
    }

    /** 在 Sa-Token Mock 上下文中以固定用户身份执行；上下文只在该调用期间有效。 */
    private static ToolRecommendVO recommend(ToolRecommendService service, ToolRecommendDTO dto) {
        return SaTokenContextMockUtil.setMockContext(() -> {
            StpKit.USER.login(USER_ID);
            return service.recommend(dto);
        });
    }

    private static void asUser(Runnable action) {
        SaTokenContextMockUtil.setMockContext(() -> {
            StpKit.USER.login(USER_ID);
            action.run();
        });
    }

    private ToolRecommendService service(Integer topK, Integer maxResults) {
        ToolRecommendService service = new ToolRecommendService(toolMapper, rentalMapper,
                recommendationMapper, vectorProvider, chatProvider, new ObjectMapper());
        if (topK != null) {
            ReflectionTestUtils.setField(service, "topK", topK);
        }
        if (maxResults != null) {
            ReflectionTestUtils.setField(service, "maxResults", maxResults);
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

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

    private static ToolRecommendDTO query(String question) {
        ToolRecommendDTO dto = new ToolRecommendDTO();
        dto.setQuestion(question);
        return dto;
    }

    private static Document doc(long toolId) {
        return new Document("tool-" + toolId, "工具文本", Map.of("toolId", toolId));
    }

    private void vectorReturns(VectorStore store, List<Document> docs) {
        vectorProvider = provider(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
    }

    /** 组装可返回任意内容的 ChatClient，用于验证重排与降级。 */
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

    @Test
    void no_candidates_returns_empty_result_without_throwing() {
        when(toolMapper.selectList(any())).thenReturn(List.of());

        ToolRecommendVO vo = recommend(service(null, null), query("a"));

        assertTrue(vo.getItems().isEmpty());
        assertTrue(vo.isDegraded(), "没有向量结果时走关键词兜底，应标记降级");
        assertNotNull(vo.getRecommendationId(), "无候选也要留下推荐记录");
    }

    @Test
    void keyword_fallback_without_terms_never_scans_whole_table() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1, "WX", "100", "AVAILABLE")));

        ToolRecommendVO vo = recommend(service(null, null), query("a"));

        assertTrue(vo.getItems().isEmpty());
        verify(toolMapper, never()).selectList(any());
    }

    @Test
    void keyword_fallback_is_bounded_by_sql_limit_and_result_limit() {
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

    @Test
    void keyword_fallback_truncates_summary_field() {
        Tool longGuide = tool(1, "WX", "100", "AVAILABLE");
        longGuide.setUsageGuide("x".repeat(5000));
        when(toolMapper.selectList(any())).thenReturn(List.of(longGuide));

        recommend(service(null, null), query("打孔"));

        assertTrue(longGuide.getUsageGuide().length() <= 300,
                "关键词兜底返回的使用说明摘要应截断");
    }

    @Test
    void vector_recall_has_a_hard_upper_limit() {
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

    @Test
    void only_available_tools_matching_category_deposit_and_time_are_recommended() {
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

    @Test
    void rerank_returns_reasons_and_keeps_model_order_in_history() {
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

    @Test
    void invalid_rerank_output_falls_back_to_heuristic_order_and_marks_degraded() {
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

    @Test
    void rerank_exception_falls_back_and_does_not_reach_caller() {
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

    @Test
    void rerank_prompt_only_carries_bounded_summary_fields() {
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

    @Test
    void click_and_feedback_only_operate_on_own_records() {
        AiRecommendation other = record(USER_ID + 1);
        when(recommendationMapper.selectById(9L)).thenReturn(other);

        ToolRecommendService service = service(null, null);
        assertThrows(BizException.class, () -> asUser(() -> service.click(9L, 1L)));
        assertThrows(BizException.class, () -> asUser(() -> service.feedback(9L, 1)));
    }

    @Test
    void click_must_reference_a_tool_from_this_recommendation() {
        when(recommendationMapper.selectById(9L)).thenReturn(record(USER_ID));

        assertThrows(BizException.class, () -> asUser(() -> service(null, null).click(9L, 99L)));
        verify(recommendationMapper, never()).updateById(any(AiRecommendation.class));
    }

    @Test
    void click_and_feedback_update_own_record() {
        when(recommendationMapper.selectById(9L)).thenReturn(record(USER_ID));
        ToolRecommendService service = service(null, null);

        asUser(() -> service.click(9L, 2L));
        asUser(() -> service.feedback(9L, 1));

        ArgumentCaptor<AiRecommendation> captor = ArgumentCaptor.forClass(AiRecommendation.class);
        verify(recommendationMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertEquals(2L, captor.getAllValues().get(0).getClickedToolId());
        assertEquals(1, captor.getAllValues().get(1).getFeedback());
    }

    @Test
    void feedback_rejects_values_outside_one_and_two() {
        assertThrows(BizException.class, () -> asUser(() -> service(null, null).feedback(9L, 3)));
        verify(recommendationMapper, never()).selectById(any());
    }

    @Test
    void history_write_failure_does_not_break_recommendation() {
        when(recommendationMapper.insert(any(AiRecommendation.class))).thenThrow(new IllegalStateException("db down"));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1, "WX", "100", "AVAILABLE")));

        ToolRecommendVO vo = recommend(service(null, null), query("打孔"));

        assertNull(vo.getRecommendationId());
        assertEquals(1, vo.getItems().size());
    }

    private static AiRecommendation record(long userId) {
        AiRecommendation record = new AiRecommendation();
        record.setId(9L);
        record.setUserId(userId);
        record.setRecommendedIds("1,2");
        return record;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<Tool>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
