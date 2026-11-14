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
 * AI 问答服务单元测试。
 * <p>
 * 测试范围：引用来源的追加与去重、模型异常时的来源兜底、非流式问答的引用展示、
 * 向量检索失败后的关键词降级、空命中提示，以及 SSE 事件顺序。
 * <p>
 * 依赖环境：Mockito 模拟 ChatClient、VectorStore 与知识库 Mapper，
 * 不调用真实模型、不连接向量库，因此用例稳定、快速且无外部成本。
 * <p>
 * 关键不变量：
 * 1. 模型未标注来源时追加“参考来源”区块，便于用户追溯答案依据；
 * 2. 模型已带 [1] 等引用时不重复追加来源区块；
 * 3. 模型或向量服务异常时仍返回可读来源，AI 故障不阻断知识检索；
 * 4. 无命中时给出明确空结果文案，而不是空白回答；
 * 5. SSE 必须先发 sources、再逐条发 message，并正常结束；
 * 6. 非流式与流式在引用展示上保持一致体验。
 * <p>
 * 断言策略：只验证最终展示文本与事件顺序，不绑定内部提示词细节；
 * 这样模型提示词调整不会轻易破坏测试，但用户可见契约始终受保护。
 */
class AiChatTest {

    /**
     * 两条固定的知识库来源，供引用与降级断言复用。
     * 内容贴近真实业务文档，既能验证标题也能验证摘要片段。
     */
    private static final List<AiChatVO.SourceVO> SOURCES = List.of(
            new AiChatVO.SourceVO("租赁规则", "押金在归还验收后按结算规则退还。"),
            new AiChatVO.SourceVO("报修指南", "使用中损坏请提交报修单并上传照片。"));

    /**
     * 场景：流式回答未包含 [1] 等引用标记。
     * 前置条件：检索返回两条来源，模型只返回一段正文。
     * 断言目标：
     * 1. 回答以模型正文开头，不能把来源区块插到正文之前；
     * 2. 正文之后追加“参考来源”区块；
     * 3. 区块包含 [1] 标题与来源摘要，用户可据此追溯答案依据；
     * 4. 追加行为只发生一次，不因流式分片而重复。
     */
    @Test
    void stream_appends_reference_block_when_model_omits_citations() {
        // 模型输出不含任何引用标记，触发服务端自动追加来源区块。
        AiService service = service(null, builder(Flux.just("押金在归还验收后结算。"), null));

        // 收集完整流并拼接，模拟前端最终展示的全文。
        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        // 模型正文必须位于最前面，保持答案主体地位。
        // 来源区块只能作为附录，不能抢占首屏。
        // 该顺序也便于前端折叠展示引用。
        assertTrue(answer.startsWith("押金在归还验收后结算。"));
        // 未标注来源时追加统一区块标题。
        assertTrue(answer.contains("参考来源"), "未标注来源时应追加参考来源区块");
        // 第一条来源以 [1] 标题形式出现，便于与正文引用对应。
        assertTrue(answer.contains("[1] 租赁规则"));
        // 来源摘要也应出现，确保用户能看到依据原文。
        // 只展示标题而没有摘要会让引用失去参考价值。
        // 这里选中第二条来源的摘要，确认所有来源都被追加。
        assertTrue(answer.contains("使用中损坏请提交报修单并上传照片。"));
    }

    /**
     * 场景：模型回答已带 [1] 引用。
     * 前置条件：流式内容分为两段返回，引用标记位于第一段。
     * 断言目标：
     * 1. 两段按原顺序拼接，结果与模型输出完全一致；
     * 2. 不再追加“参考来源”区块，避免重复引用；
     * 3. 判断依据是全文是否已含引用标记，而不是只看最后一片；
     * 4. 该用例保护“模型自觉标注”时的降噪体验。
     */
    @Test
    void stream_keeps_model_citations_without_duplicating_reference_block() {
        // 引用标记在第一段，后续片段不再包含，验证跨片判断正确。
        AiService service = service(null, builder(Flux.just("依据[1]，", "押金在归还验收后结算。"), null));

        // 拼接顺序必须与流式输出顺序一致。
        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        // 全文精确等于模型输出，说明没有插入任何额外文本。
        // 使用 assertEquals 而非 contains，可发现多余空格或换行。
        // 这是“去重”语义最严格的表达方式。
        assertEquals("依据[1]，押金在归还验收后结算。", answer);
        // 已有引用时不再追加参考来源区块。
        // 不追加并不代表来源丢失，前端仍可通过 sources 字段展示引用。
        // 该分支与上一用例共同构成“追加 / 不追加”的完整矩阵。
        assertFalse(answer.contains("参考来源"));
    }

    /**
     * 场景：流式模型调用直接报错。
     * 前置条件：Flux 以 error 结束，模拟模型服务不可用或超时。
     * 断言目标：
     * 1. 流仍能正常结束，不把异常抛给调用方；
     * 2. 返回包含来源标题与摘要的可读文本；
     * 3. AI 故障时用户至少能看到知识库原文，而不是空白或错误页；
     * 4. 该兜底是问答功能可用性的最后一道保障。
     */
    @Test
    void stream_falls_back_to_sources_and_completes_when_llm_fails() {
        // 用 Flux.error 模拟模型调用失败，而不是 mock 抛异常。
        AiService service = service(null, builder(Flux.error(new IllegalStateException("llm down")), null));

        // collectList 能正常完成，说明异常已被服务内部吞掉并降级。
        // 若异常继续传播，block 会直接抛出，而不是返回可读文本。
        // 这是流式链路可用性的关键断言。
        String answer = join(service.streamAnswer("押金怎么退", SOURCES).collectList().block());

        // 降级文本至少包含来源标题，用户可识别这是知识库原文。
        assertTrue(answer.contains("租赁规则"));
        // 来源摘要同样保留，保证内容有实际参考价值。
        // 降级不是返回错误码，而是返回知识库原文，
        // 因此用户体验从“AI 回答”平滑退化为“检索结果”。
        assertTrue(answer.contains("押金在归还验收后按结算规则退还。"));
    }

    /**
     * 场景：非流式问答，向量检索命中一条文档，模型未标注引用。
     * 前置条件：ChatClient 返回固定答案，文档元数据包含标题。
     * 断言目标：
     * 1. sources 恰好一条，来源数量与向量命中一致；
     * 2. 答案包含“参考来源”与 [1] 标题；
     * 3. 非流式与流式使用相同的引用追加逻辑，体验一致；
     * 4. 来源列表与答案正文同时可用，前端可分别渲染。
     */
    @Test
    void non_stream_chat_also_returns_visible_citations() {
        // 向量库返回一条带 title 元数据的文档。
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(new org.springframework.ai.document.Document(
                        "doc-1", "押金在归还验收后按结算规则退还。", java.util.Map.of("title", "租赁规则"))));
        // 模型直接返回正文，且不包含引用标记。
        AiService service = service(store, builder(null, "押金在归还验收后结算。"));

        // 非流式接口一次返回来源列表与完整答案。
        AiChatVO vo = service.chat("押金怎么退");

        // 来源数量必须与命中文档数一致，不能去重丢失。
        // 来源列表由检索层产出，答案正文由模型产出，二者需同时可用。
        // 该断言也验证非流式分支没有跳过引用追加逻辑。
        assertEquals(1, vo.getSources().size());
        // 答案正文同样追加参考来源区块。
        // 非流式与流式共用同一追加函数，避免两套逻辑漂移。
        // 若此处失败而流式用例通过，说明分支实现被拆开了。
        assertTrue(vo.getAnswer().contains("参考来源"));
        // 来源标题以 [1] 形式出现在正文里。
        assertTrue(vo.getAnswer().contains("[1] 租赁规则"));
    }

    /**
     * 场景：向量检索抛出异常，模拟嵌入服务不可用。
     * 前置条件：知识库 Mapper 的关键词查询能命中文档。
     * 断言目标：
     * 1. retrieve 不向上抛异常，而是切换到关键词检索；
     * 2. 返回 1 条关键词命中的来源；
     * 3. 标题正确、摘要包含用户问题中的关键词；
     * 4. 证明向量故障时问答仍可降级运行，而不是整体不可用。
     */
    @Test
    void vector_failure_degrades_to_keyword_search() {
        // 向量检索直接抛异常，触发服务的降级分支。
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new IllegalStateException("embedding down"));
        // 关键词检索准备一条可命中的知识库文档。
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle("租赁规则");
        doc.setContent("押金在归还验收后按结算规则退还。");
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(doc));

        // 直接调用 retrieve，聚焦检索层而非完整问答链路。
        List<AiChatVO.SourceVO> sources = service(store, provider(mapper), null).retrieve("押金怎么退");

        // 降级后仍返回一条来源。
        // 关键词检索直接查 Mapper，不依赖向量库是否可用。
        // 这保证了嵌入服务故障时问答功能仍可部分使用。
        assertEquals(1, sources.size());
        // 来源标题来自知识库文档，而不是向量元数据。
        assertEquals("租赁规则", sources.get(0).getTitle());
        // 摘要包含问题关键词，说明关键词命中逻辑生效。
        // 若摘要为空或与问题无关，降级结果对用户没有帮助。
        // 这里只断言包含关键词，不绑定摘要截断长度。
        assertTrue(sources.get(0).getSnippet().contains("押金"));
    }

    /**
     * 场景：向量与关键词都没有命中，问题也不属于工具租赁领域。
     * 前置条件：知识库 Mapper 返回空列表，模型不返回内容。
     * 断言目标：
     * 1. sources 为空列表而非 null；
     * 2. 答案包含“暂未在知识库中找到相关说明”；
     * 3. 给用户明确反馈，而不是空白回答或误导性内容；
     * 4. 空结果同样走正常响应结构，前端无需特殊分支。
     */
    @Test
    void empty_retrieval_returns_empty_result_message() {
        // 同时提供空知识库与空模型输出，构造最彻底的无命中场景。
        AiService service = service(null, provider(emptyMapper()), builder(null, null));

        // 非流式接口返回统一结构，便于直接断言 sources 与 answer。
        AiChatVO vo = service.chat("今天天气");

        // 无命中时来源列表必须为空，而不是 null。
        // 空列表让前端可以直接渲染“无来源”状态。
        // 若返回 null，前端遍历时会抛异常或需要额外判空。
        assertTrue(vo.getSources().isEmpty());
        // 答案给出明确空结果文案，避免用户以为系统卡死。
        // 文案由服务端统一下发，前端无需区分“无来源”和“无答案”。
        // 该用例同时确认空命中也走正常响应结构，而非抛异常。
        assertTrue(vo.getAnswer().contains("暂未在知识库中找到相关说明"));
    }

    /**
     * 场景：控制器生成 SSE 事件流，供前端逐字渲染。
     * 前置条件：retrieve 返回两条来源，streamAnswer 返回两段增量。
     * 断言目标：
     * 1. 共 3 个事件，不多不少；
     * 2. 第一个必须是 sources，且 data 包含来源标题；
     * 3. 后两个为 message 增量，顺序与模型输出一致；
     * 4. 流能正常结束，客户端可收到完成信号；
     * 5. 来源先于正文，前端才能先建立引用上下文再渲染答案。
     */
    @Test
    void sse_emits_sources_before_messages_and_completes() {
        // 用 mock 固定检索与流式回答，隔离真实模型与向量库。
        AiService aiService = mock(AiService.class);
        when(aiService.retrieve("怎么预约")).thenReturn(SOURCES);
        when(aiService.streamAnswer(eq("怎么预约"), anyList())).thenReturn(Flux.just("答", "案"));
        // 控制器只依赖服务与 ObjectMapper，推荐服务在此场景不参与。
        UserAiController controller = new UserAiController(
                aiService, mock(ToolRecommendService.class), new ObjectMapper());
        AiChatDTO dto = new AiChatDTO();
        dto.setQuestion("怎么预约");

        // 收集全部 SSE 事件，模拟客户端完成一次流式读取。
        List<ServerSentEvent<String>> events = controller.chatStream(dto).collectList().block();

        // 来源事件 + 两段正文事件 = 3 个事件。
        // 数量精确断言可发现重复发送来源或丢失增量的问题。
        // 事件顺序比内容更重要，因为前端按到达顺序拼装 UI。
        assertEquals(3, events.size(), "应先发来源事件、再发两条回答增量并正常结束");
        // 第一个事件必须是来源，保证引用先于正文到达。
        assertEquals("sources", events.get(0).event());
        // 来源事件携带标题，前端可直接渲染引用卡片。
        assertTrue(events.get(0).data().contains("租赁规则"));
        // 后续均为 message 增量，顺序与 Flux 输出一致。
        // 两段内容分别是“答”与“案”，前端逐段追加即可得到完整回答。
        // 这里只验证事件类型与顺序，不重复断言拼接文本。
        assertEquals("message", events.get(1).event());
        assertEquals("message", events.get(2).event());
    }

    /**
     * 辅助方法（重载）：用空知识库 Mapper 构造 AiService。
     * 适用于不关心关键词降级的用例，减少重复样板。
     */
    private static AiService service(VectorStore store, ChatClient.Builder builder) {
        return service(store, provider(emptyMapper()), builder);
    }

    /**
     * 辅助方法：按依赖注入形态组装 AiService。
     * 通过 ObjectProvider 包装依赖，模拟 Spring 的可选注入语义；
     * 传入 null 时表示该能力不可用，正好覆盖降级分支。
     */
    private static AiService service(VectorStore store, ObjectProvider<KnowledgeDocMapper> mapper,
                                    ChatClient.Builder builder) {
        return new AiService(mapper.getIfAvailable(), provider(store), provider(builder));
    }

    /**
     * 辅助方法：把流式片段拼接为完整字符串，空列表返回空串。
     * 空列表与 null 都归一为空串，便于断言最终展示文本。
     */
    private static String join(List<String> parts) {
        return parts == null ? "" : String.join("", parts);
    }

    /**
     * 辅助方法：构造关键词查询始终返回空的知识库 Mapper。
     * 用于隔离关键词降级，确保测试只观察向量或模型分支。
     */
    private static KnowledgeDocMapper emptyMapper() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        return mapper;
    }

    /**
     * 辅助方法：用显式 mock 组装 ChatClient.Builder → ChatClient → prompt → stream/call 调用链。
     * 可按需注入流式片段、普通答案或模拟调用失败。
     * stream 为 null 时返回空 Flux，answer 为 null 时普通调用返回 null。
     */
    private static ChatClient.Builder builder(Flux<String> stream, String answer) {
        // 逐层 mock 构建器与调用规格，模拟真实链式 API 的返回。
        // 每一层的返回值都是下一层 mock，形成可控的调用链。
        // 这样无需真实模型即可稳定覆盖 stream 与 call 两条分支。
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        // build() 返回客户端，prompt() 返回可继续链式调用的规格。
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        // system/user 原样返回自身，允许无限链式调用。
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        // stream()/call() 分别对应流式与非流式分支。
        when(spec.stream()).thenReturn(streamSpec);
        when(spec.call()).thenReturn(callSpec);
        // 未提供流时返回空 Flux，避免 NPE。
        // 空 Flux 会正常完成，便于测试“无模型输出”的兜底路径。
        // answer 为 null 时普通调用返回 null，由服务自行处理。
        when(streamSpec.content()).thenReturn(stream == null ? Flux.empty() : stream);
        when(callSpec.content()).thenReturn(answer);
        return builder;
    }

    /**
     * 辅助方法：模拟 ObjectProvider，按需返回依赖或 null。
     * getIfAvailable() 是服务读取可选依赖的唯一入口，因此只需 mock 该方法。
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
