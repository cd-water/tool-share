package com.cdwater.toolshare.module.ai;

import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 问答的引用溯源、降级与事件流顺序：向量失败降级关键词、模型异常/未标注时输出来源、
 * 无命中时给出空结果文案，SSE 始终先 sources 再 message 并正常结束。
 */
class AiChatTest {

    private static final List<AiChatVO.SourceVO> SOURCES = List.of(
            new AiChatVO.SourceVO("租赁规则", "押金在归还验收后按结算规则退还。"),
            new AiChatVO.SourceVO("报修指南", "使用中损坏请提交报修单并上传照片。"));

    @Test
    void stream_appends_reference_block_when_model_omits_citations() {
        AiService service = service(null, builder(Flux.just("押金在归还验收后结算。"), null));

        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        assertTrue(answer.startsWith("押金在归还验收后结算。"));
        assertTrue(answer.contains("参考来源"), "未标注来源时应追加参考来源区块");
        assertTrue(answer.contains("[1] 租赁规则"));
        assertTrue(answer.contains("使用中损坏请提交报修单并上传照片。"));
    }

    @Test
    void stream_keeps_model_citations_without_duplicating_reference_block() {
        AiService service = service(null, builder(Flux.just("依据[1]，", "押金在归还验收后结算。"), null));

        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        assertEquals("依据[1]，押金在归还验收后结算。", answer);
        assertFalse(answer.contains("参考来源"));
    }

    @Test
    void stream_falls_back_to_sources_and_completes_when_llm_fails() {
        AiService service = service(null, builder(Flux.error(new IllegalStateException("llm down")), null));

        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        assertTrue(answer.contains("租赁规则"));
        assertTrue(answer.contains("押金在归还验收后按结算规则退还。"));
    }

    @Test
    void non_stream_chat_also_returns_visible_citations() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(new org.springframework.ai.document.Document(
                        "doc-1", "押金在归还验收后按结算规则退还。", java.util.Map.of("title", "租赁规则"))));
        AiService service = service(store, builder(null, "押金在归还验收后结算。"));

        AiChatVO vo = service.chat("押金怎么退");

        assertEquals(1, vo.getSources().size());
        assertTrue(vo.getAnswer().contains("参考来源"));
        assertTrue(vo.getAnswer().contains("[1] 租赁规则"));
    }

    @Test
    void vector_failure_degrades_to_keyword_search() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new IllegalStateException("embedding down"));
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle("租赁规则");
        doc.setContent("押金在归还验收后按结算规则退还。");
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(doc));

        List<AiChatVO.SourceVO> sources = service(store, provider(mapper), null).retrieve("押金怎么退");

        assertEquals(1, sources.size());
        assertEquals("租赁规则", sources.get(0).getTitle());
        assertTrue(sources.get(0).getSnippet().contains("押金"));
    }

    @Test
    void empty_retrieval_returns_empty_result_message() {
        AiService service = service(null, provider(emptyMapper()), builder(null, null));

        AiChatVO vo = service.chat("今天天气");

        assertTrue(vo.getSources().isEmpty());
        assertTrue(vo.getAnswer().contains("暂未在知识库中找到相关说明"));
    }

    @Test
    void sse_emits_sources_before_messages_and_completes() {
        AiService aiService = mock(AiService.class);
        when(aiService.retrieve("怎么预约")).thenReturn(SOURCES);
        when(aiService.streamAnswer(eq("怎么预约"), anyList())).thenReturn(Flux.just("答", "案"));
        UserAiController controller = new UserAiController(
                aiService, mock(ToolRecommendService.class), new ObjectMapper());
        AiChatDTO dto = new AiChatDTO();
        dto.setQuestion("怎么预约");

        List<ServerSentEvent<String>> events = controller.chatStream(dto).collectList().block();

        assertEquals(3, events.size(), "应先发来源事件、再发两条回答增量并正常结束");
        assertEquals("sources", events.get(0).event());
        assertTrue(events.get(0).data().contains("租赁规则"));
        assertEquals("message", events.get(1).event());
        assertEquals("message", events.get(2).event());
    }

    private static AiService service(VectorStore store, ChatClient.Builder builder) {
        return service(store, provider(emptyMapper()), builder);
    }

    private static AiService service(VectorStore store, ObjectProvider<KnowledgeDocMapper> mapper,
                                    ChatClient.Builder builder) {
        return new AiService(mapper.getIfAvailable(), provider(store), provider(builder));
    }

    private static String join(List<String> parts) {
        return parts == null ? "" : String.join("", parts);
    }

    private static KnowledgeDocMapper emptyMapper() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        return mapper;
    }

    /** 用显式 mock 组装 ChatClient 调用链，避免测试依赖外部模型。 */
    private static ChatClient.Builder builder(Flux<String> stream, String answer) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(spec.call()).thenReturn(callSpec);
        when(streamSpec.content()).thenReturn(stream == null ? Flux.empty() : stream);
        when(callSpec.content()).thenReturn(answer);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
