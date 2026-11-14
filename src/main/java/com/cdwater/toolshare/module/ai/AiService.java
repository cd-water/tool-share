package com.cdwater.toolshare.module.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import com.cdwater.toolshare.module.ai.AiChatVO;
import com.cdwater.toolshare.shared.ai.TextChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 知识库问答服务：负责文档切块索引、向量检索、关键词降级与大模型回答生成。
 * <p>检索策略：
 * <ol>
 *   <li>优先使用向量库按语义召回知识片段；</li>
 *   <li>向量库未配置、调用异常或没有命中时，降级为关键词打分检索；</li>
 *   <li>最多取 {@link #MAX_SOURCES} 条来源，并限制拼接进 prompt 的字符数。</li>
 * </ol>
 * <p>生成策略：把来源按编号拼入提示词，要求模型用 [1]、[2] 标注依据；
 * 模型不可用、超时或输出为空时，直接返回知识库片段组成的兜底回答，
 * 保证接口始终有可展示内容。若模型没有标注来源，则自动追加「参考来源」
 * 区块，确保居民能看到答案出处。
 * <p>流式响应：SSE 先发送来源事件，再逐条转发模型增量；任何异常都会
 * 转为正常结束并输出兜底文案，避免前端连接悬挂。
 * <p>索引策略：文档按固定大小切块，chunk 主键由文档 ID 确定性派生，
 * 因此重建索引可以先删后写且保持幂等。外部向量库失败时返回 false，
 * 由知识库服务保留 indexed=false 并重试。
 * <p>为什么检索与生成分离：即使大模型不可用，知识库片段本身也能作为
 * 兜底答案返回；把检索独立出来后，降级路径可以复用同一份来源。
 * <p>为什么限制来源数量与上下文字符：知识片段越多，提示词越长，成本
 * 与延迟越高，且模型更容易被无关内容干扰。4 条来源、1600 字符是
 * 在召回率与成本之间取的折中值。
 * <p>线程模型：同步问答使用 boundedElastic 调度阻塞式模型调用；流式
 * 问答直接返回 Reactor 流，由 WebFlux 负责背压与取消。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    /** 切块器：命名空间 kb，块大小 700 字符，块间重叠 80 字符，最小块 60 字符。 */
    private static final TextChunker CHUNKER = new TextChunker("kb", 700, 80, 60);
    /** 单次问答最多召回的知识片段数，避免把整库内容拼进 prompt。 */
    // 同时限制来源展示数量，避免回答下方来源列表过长。
    private static final int MAX_SOURCES = 4;
    /** 拼进 prompt 的知识库上下文上限（字符）。 */
    // 超出部分直接截断，控制 token 成本与模型上下文窗口。
    private static final int MAX_CONTEXT_CHARS = 1600;
    /** 单次大模型调用超时，超时后降级为知识库兜底回答。 */
    // 同步与流式调用共用同一超时，避免请求长时间挂起。
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(20);
    /** 匹配模型输出中的 [1]、[2] 形式引用标记。 */
    private static final Pattern CITATION = Pattern.compile("\\[\\d+]");

    /** 知识库文档访问入口，关键词降级检索时读取全部文档。 */
    private final KnowledgeDocMapper knowledgeDocMapper;
    /** 向量库提供者；未配置向量库时返回 null，检索与索引自动降级。 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 大模型客户端提供者；未配置模型时直接使用知识库兜底回答。 */
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    /**
     * <p>流程：校验参数 → 删除旧 chunk → 切块 → 写入向量库。删除与写入
     * 使用同一套确定性主键，因此重复调用不会产生重复 chunk。
     * <p>索引成功只表示 chunk 已写入，不保证嵌入模型质量。
     * <p>切块与写入都不修改知识库文档本身，索引失败可安全重试。
     * <p>重复执行安全：先按确定性主键删除旧 chunk，再写入新 chunk。
     * <p>向量库未配置时返回 false，调用方会保留文档并等待后续重试。
     * <p>失败不抛异常：知识库服务据此保留 indexed=false 并进入重试流程。
     */
    public boolean index(KnowledgeDoc doc) {
    // 索引是幂等的，重复执行不会产生重复 chunk。
    // 失败只影响 indexed 标记。
    // 不修改文档正文。
        // 向量库未配置、文档为空或正文为空时直接失败。
        // 删除与写入使用同一批确定性主键。
        // 索引失败不抛异常，由知识库服务决定是否重试。
        // 索引步骤：校验可用性 → 删除旧 chunk → 切块 → 批量写入。
        // 任何一步失败都返回 false，不向调用方抛出异常。
        // 写入使用确定性主键，因此重复索引不会产生重复向量。
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 缺少向量库、文档 ID 或正文时无法建立索引，直接返回失败。
        if (vectorStore == null || doc == null || doc.getId() == null || !StringUtils.hasText(doc.getContent())) {
            return false;
        }
        try {
            // 先清理旧 chunk：文档更新后重新索引时避免新旧内容同时被检索。
            vectorStore.delete(chunkIds(doc.getId()));
            // 按固定大小切块，并为每块生成确定性主键与元数据。
            List<Document> chunks = split(doc);
            // 空正文不会产生 chunk，此时视为索引成功但无内容可检索。
            if (!chunks.isEmpty()) {
                // 批量写入向量库；部分实现内部会再次调用嵌入模型。
                vectorStore.add(chunks);
            }
            return true;
        } catch (Exception e) {
            // 记录失败原因但不抛异常，调用方保留文档并等待重试。
            log.warn("知识库向量索引失败（不影响文档保存）: {}", e.getMessage());
            return false;
        }
    }

/**
 * 清空文档的全部向量 chunk。
 * <p>按文档 ID 推导固定范围的确定性主键，不依赖文档内容是否仍在数据库，
 * 因此即使文档已被删除或内容变更，也能准确清理旧向量。
 * <p>未配置向量库时视为无需清理，返回 true；清理失败只记录日志并返回
 * false，由知识库服务中断删除流程。
 *
 * @param docId 文档主键
 * <p>清理失败只记录日志并返回 false。
 * @return 是否已清理；未配置向量库时视为无需清理
 */
    public boolean removeIndex(Long docId) {
    // 删除按确定性主键批量执行。
    // 未配置向量库时直接成功。
    // 失败由调用方决定是否中断删除。
        // 未配置向量库时没有需要清理的数据，直接成功。
        // 否则按文档 ID 推导全部 chunk 主键并批量删除。
        // 清理失败返回 false，由知识库服务中断删除流程。
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 未配置向量库时没有需要清理的数据，按成功处理。
        if (vectorStore == null || docId == null) {
            return true;
        }
        try {
            // 一次性删除该文档的全部确定性 chunk 主键。
            vectorStore.delete(chunkIds(docId));
            return true;
        } catch (Exception e) {
            // 清理失败返回 false，由知识库服务中断删除并提示重试。
            log.warn("删除向量索引失败 docId={}: {}", docId, e.getMessage());
            return false;
        }
    }

    /**
     * 同步知识库问答。
     * <p>来源为空时回答使用兜底文案。
     * <p>来源顺序与回答中的编号一致，均为 1 开始。
     * <p>回答与来源成对返回，前端可渲染引用角标。
     * <p>同步接口不返回降级标记，兜底文案本身已说明情况。
     * <p>先检索来源，再生成回答；生成失败时使用兜底回答。无论模型是否
     * 标注来源，最终都会补齐「参考来源」区块。
     *
     * @param question 居民问题
     * <p>回答保证非空；模型不可用时返回知识库兜底文案。
     * @return 回答与参考来源
     */
    public AiChatVO chat(String question) {
    // 同步接口返回完整回答与来源。
    // 模型不可用时使用兜底文案。
    // 回答保证非空。
        // 检索最多返回四条来源。
        // 生成答案时只把来源片段放进提示词。
        // 没有答案时使用知识库兜底文案。
        // 同步问答：先检索来源，再生成回答。
        // 生成失败时由 withCitations 回退到知识库文案。
        // 返回前统一补齐引用编号与参考来源区块。
        // 检索与生成分离：检索失败会走关键词降级，不会让整个问答失败。
        List<AiChatVO.SourceVO> sources = retrieve(question);
        // 统一在返回前补齐引用与兜底文案，保证 answer 非空。
        String answer = withCitations(generateAnswer(question, sources), sources);
        // 组装响应：回答与来源成对返回，前端可渲染引用角标。
        AiChatVO vo = new AiChatVO();
        // answer 此时已保证非空。
        vo.setAnswer(answer);
        // 来源顺序与回答中的编号一致。
        vo.setSources(sources);
        // 同步接口不返回降级标记，兜底文案本身已说明情况。
        return vo;
    }

    /** 检索知识库来源：向量优先，异常或为空时降级为关键词检索。 */
    public List<AiChatVO.SourceVO> retrieve(String question) {
    // 检索结果最多四条。
    // 向量失败时自动降级。
    // 来源顺序与回答引用编号一致。
        // 检索不修改知识库数据。
        // 结果按相关性排序。
        // 来源数量受 MAX_SOURCES 限制。
        // 检索入口保持单一职责，方便后续替换检索实现。
        // 向量优先、关键词兜底的策略封装在 search 中。
        // 检索结果数量由 MAX_SOURCES 限制。
        // 检索入口只做转发，便于后续替换检索实现而不影响调用方。
        return search(question);
    }

    /**
     * 流式生成回答；外部 AI 不可用或异常时流式输出兜底文案，保证事件流完整结束。
     * 模型未用 [序号] 标注来源或没有返回内容时，追加「参考来源」区块并随流输出。
     * <p>实现上使用 Flux.defer 延迟创建模型调用，确保每次订阅都重新执行；
     * 已生成文本通过 StringBuilder 累积，流结束后再决定是否补来源区块。
     * <p>已发送的增量不会被撤回，来源区块在末尾统一补齐。
     * <p>超时与模型异常共用同一降级路径，前端只会看到正常结束的流。
     * <p>已生成的增量不会因后续异常被撤回；来源区块只在流结束后追加一次。
     * <p>Flux.defer 保证每次订阅都重新执行模型调用，适合 SSE 重连场景。
     * <p>模型异常只记录日志并结束当前流，不把错误事件推给前端。
     */
    public Flux<String> streamAnswer(String question, List<AiChatVO.SourceVO> sources) {
        // 来源事件由控制器单独发送。
        // 这里只负责回答增量与来源尾部。
        // 流结束时客户端即可认为回答完成。
        // 未配置模型或没有来源时直接输出兜底文案，保持事件流完整。
        // 模型增量边转发边累积，流结束后判断是否补来源区块。
        // 超时或异常只结束当前流，不向前端发送错误事件。
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return Flux.just(fallbackAnswer(sources));
        }
        String userPrompt = userPrompt(question, sources);
        return Flux.defer(() -> {
            // 累积已输出的增量，用于判断模型是否标注了来源。
            StringBuilder answer = new StringBuilder();
            // 每次订阅都新建调用，避免复用已被消费的流。
            Flux<String> generated = Flux.defer(() -> builder.build().prompt()
                            .system(SYSTEM_PROMPT)
                            .user(userPrompt)
                            .stream()
                            .content())
                    // 过滤空增量，防止前端收到无意义的事件。
                    .filter(StringUtils::hasText)
                    .timeout(LLM_TIMEOUT)
                    // 边转发边累积，供尾部判断是否需要追加来源。
                    .doOnNext(answer::append)
                    .onErrorResume(e -> {
                        log.warn("AI 流式生成失败，降级为知识库检索: {}", e.getMessage());
                        return Flux.empty();
                    });
            // 模型流结束后再拼接来源区块，确保其出现在回答末尾。
            return generated.concatWith(Flux.defer(() -> {
                // 已标注来源时返回 null；未标注或为空时返回参考来源区块。
                String tail = citationTail(answer.toString(), sources);
                // 没有需要追加的内容时返回空流，不影响已发送的增量。
                return StringUtils.hasText(tail) ? Flux.just(tail) : Flux.empty();
            }));
        });
    }

    /**
     * 调用大模型生成同步回答。
     * <p>使用 boundedElastic 调度避免阻塞 Web 线程，并施加统一超时；
     * 任何异常都降级为 null，由调用方生成知识库兜底文案。
     *
     * @param question 居民问题
     * @param sources  检索到的知识来源
     * <p>模型异常统一降级为 null，由调用方生成知识库兜底文案。
     * @return 模型回答；不可用时返回 null
     */
    private String generateAnswer(String question, List<AiChatVO.SourceVO> sources) {
        // 模型不可用时返回 null。
        // 超时与异常走同一降级路径。
        // 调用线程由 boundedElastic 提供。
        // 使用 boundedElastic 执行阻塞式模型调用，避免占用 Web 线程。
        // 统一超时，超时与异常都返回 null，交由调用方兜底。
        // 不修改来源列表，只读取片段拼接提示词。
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return null;
        }
        String userPrompt = userPrompt(question, sources);
        try {
            // 把阻塞式模型调用包装为 Mono，便于统一超时与调度。
            return Mono.fromCallable(() -> builder.build().prompt()
                            .system(SYSTEM_PROMPT)
                            .user(userPrompt)
                            .call()
                            .content())
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(LLM_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("AI 生成回答失败，降级为知识库检索: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("AI 生成回答失败，降级为知识库检索: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 为同步回答补齐引用与兜底文案。
     * <p>模型没有返回内容时直接使用知识库兜底；已返回内容但未标注来源时，
     * 在末尾追加参考来源区块。
     *
     * @param answer  模型回答，可为 null
     * @param sources 知识来源
     * <p>模型没有返回内容时直接使用知识库兜底。
     * @return 最终回答，保证非空
     */
    private String withCitations(String answer, List<AiChatVO.SourceVO> sources) {
        // 模型没有返回内容时直接使用知识库兜底文案。
        // 模型已标注来源时不重复追加参考区块。
        // 返回值保证非空，前端可以直接展示。
        if (!StringUtils.hasText(answer)) {
            return fallbackAnswer(sources);
        }
        // tail 为 null 表示模型已标注来源，无需追加。
        String tail = citationTail(answer, sources);
        // 需要追加时直接拼接在回答末尾。
        return tail == null ? answer : answer + tail;
    }

    /** 模型已标注来源时返回 null，否则返回要追加的参考来源区块。 */
    private String citationTail(String answer, List<AiChatVO.SourceVO> sources) {
        // 用正则检测 [1]、[2] 形式的引用标记。
        // 只要出现任意标记就认为模型已给出出处。
        // 没有标记时生成完整的「参考来源」区块。
        if (!StringUtils.hasText(answer)) {
            return fallbackAnswer(sources);
        }
        // 只要出现任意 [n] 标记就认为模型已给出引用，不再重复追加。
        return CITATION.matcher(answer).find() ? null : sourceBlock(sources);
    }

    /**
     * 生成知识库兜底回答。
     * <p>有来源时列出参考片段并建议联系工作人员；没有来源时明确告知
     * 未找到相关说明，避免模型不可用时给出空回答。
     *
     * @param sources 知识来源，可为空
     * <p>有来源时列出参考片段，没有来源时提示联系工作人员。
     * @return 可展示的兜底文案
     */
    private String fallbackAnswer(List<AiChatVO.SourceVO> sources) {
        // 没有来源时明确告知未找到，并建议联系社区工作人员。
        // 有来源时直接列出片段，不再调用模型。
        // 兜底文案不编造任何业务规则。
        // 无来源时只提示联系工作人员，不编造任何规则。
        if (sources.isEmpty()) {
            // 文案同时给出两种后续动作，降低居民困惑。
            return "暂未在知识库中找到相关说明。建议您联系社区服务站工作人员，或换个关键词再试。";
        }
        // 有来源时直接列出片段，并说明可进一步联系工作人员。
        return "根据社区工具知识库，为您找到以下相关信息：" + sourceBlock(sources)
                + "\n如需更准确的答复，请联系社区服务站工作人员。";
    }

    /**
     * 把来源列表渲染为带编号的「参考来源」区块。
     * <p>编号与 prompt 中的编号一致，前端和居民都能据此对应回答中的引用。
     *
     * @param sources 知识来源
     * <p>编号从 1 开始，与提示词中的来源编号一致。
     * @return 形如「参考来源：[1] 标题：片段」的文本
     */
    private String sourceBlock(List<AiChatVO.SourceVO> sources) {
        // 按来源顺序生成带编号的区块。
        // 编号从 1 开始，与提示词中的编号一致。
        // 片段可能已经包含省略号，这里不再二次截断。
        // 前置两个换行，与模型回答自然分隔。
        StringBuilder block = new StringBuilder("\n\n参考来源：");
        for (int i = 0; i < sources.size(); i++) {
            AiChatVO.SourceVO source = sources.get(i);
            // 编号从 1 开始，与提示词中的来源编号保持一致。
            block.append("\n[").append(i + 1).append("] ").append(source.getTitle())
                    .append("：").append(source.getSnippet());
        }
        return block.toString();
    }

    /**
     * 拼接发送给大模型的用户提示词。
     * <p>知识库上下文在前、居民问题在后，并明确以「居民问题：」分隔，
     * 降低模型把问题内容误当作知识库指令的风险。
     *
     * @param question 居民问题
     * @param sources  知识来源
     * <p>知识库上下文在前、居民问题在后，降低问题内容被当作指令的风险。
     * @return 用户提示词
     */
    private String userPrompt(String question, List<AiChatVO.SourceVO> sources) {
        // 知识库上下文在前、居民问题在后。
        // 用明确的分隔标题降低提示词注入风险。
        // 上下文已经过字符预算截断。
        // 来源片段按编号拼接，供模型在回答中引用。
        return "知识库内容：\n" + buildContext(sources) + "\n\n居民问题：" + question;
    }

/**
 * 拼接知识库上下文并做字符预算截断。
 * <p>按来源顺序累积，达到上限后停止追加，并在必要时截断最后一段，
 * 防止 prompt 无界增长。来源编号与最终回答中的引用编号保持一致。
 *
 * @param sources 知识来源
 * <p>来源编号在截断后仍然保持连续。
 * @return 截断后的上下文文本
 */
    private String buildContext(List<AiChatVO.SourceVO> sources) {
        // 逐条按编号拼接标题与片段。
        // 达到字符预算后停止追加。
        // 最后统一截断，保证不超过 MAX_CONTEXT_CHARS。
        // 使用 StringBuilder 顺序累积，保持来源编号与顺序。
        StringBuilder context = new StringBuilder();
        // 达到字符预算后立即停止，避免无意义的后续拼接。
        for (int i = 0; i < sources.size() && context.length() < MAX_CONTEXT_CHARS; i++) {
            AiChatVO.SourceVO source = sources.get(i);
            // 每个来源前标注编号与标题，方便模型引用。
            context.append("\n[").append(i + 1).append("] 【").append(source.getTitle()).append("】\n")
                    .append(source.getSnippet());
        }
        // 最后一段可能使总长度略微超出，统一截断到预算内。
        return context.length() > MAX_CONTEXT_CHARS
                ? context.substring(0, MAX_CONTEXT_CHARS) : context.toString();
    }

    /**
     * 系统提示词：约束回答范围、语气与引用格式。
     * 没有答案时明确说明并建议联系社区工作人员。
     * <p>提示词不包含系统内部字段，只包含可公开展示的片段。
     * <p>来源编号要求与上下文中的编号一致，便于前端与居民对照。
     * <p>提示词只包含检索到的片段，不包含整库内容，控制上下文长度。
     * <p>明确要求「不要编造规则」，降低模型在知识库缺失时自由发挥的风险。
     * <p>要求模型只依据知识库回答、不编造规则，并使用 [1]、[2] 标注来源；
     */
    private static final String SYSTEM_PROMPT = "你是社区共享工具租赁系统的服务助手。请只依据提供的知识库内容，"
            + "用简洁、友好的中文回答居民问题；如果知识库没有答案，请明确说明并建议联系社区工作人员。"
            + "回答中可以分点说明，但不要编造规则。"
            + "请在回答中用 [1]、[2] 这样的编号标注所依据的来源，编号与知识库内容前的编号一致。";

    /**
     * 执行知识库检索：向量优先，失败或为空时降级为关键词检索。
     * <p>向量异常只记录 warn 日志，不影响关键词降级。
     * <p>未配置向量库时直接跳过向量分支。
     * <p>向量库异常不会向上抛出，而是转为关键词降级。
     * <p>向量结果的标题来自 chunk 元数据；未配置标题时使用「知识库」占位。
     *
     * @param question 居民问题
     * <p>向量异常或未命中时自动转为关键词降级。
     * @return 检索到的来源列表，可能为空
     */
    private List<AiChatVO.SourceVO> search(String question) {
        // 向量库可用时优先做语义检索。
        // 检索异常或结果为空时转为关键词检索。
        // 两种路径返回相同结构的来源列表。
        // 统一收集向量结果；为空时再走关键词降级。
        List<AiChatVO.SourceVO> result = new ArrayList<>();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 未配置向量库时直接跳过，避免无意义的异常捕获。
        if (vectorStore != null) {
            try {
                // 语义检索，按相似度返回最多 MAX_SOURCES 条 chunk。
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        // 居民原始问题直接作为查询文本。
                        .query(question)
                        // 限制召回数量，控制后续 prompt 大小。
                        .topK(MAX_SOURCES)
                        // 不做相似度阈值过滤，由数量上限与后续降级控制质量。
                        .similarityThresholdAll()
                        .build());
                // 部分实现可能返回 null，按未命中处理。
                if (docs != null) {
                    // 逐条转换为视图来源，保留相似度顺序。
                    for (Document doc : docs) {
                        // 元数据中的 title 由索引阶段写入。
                        Map<String, Object> metadata = doc.getMetadata();
                        // 历史 chunk 可能缺少标题，使用占位名保证展示完整。
                        String title = metadata.get("title") == null
                                ? "知识库" : String.valueOf(metadata.get("title"));
                        // 片段围绕问题关键词截取，便于居民快速定位。
                        result.add(new AiChatVO.SourceVO(title, snippet(doc.getText(), question)));
                    }
                }
            } catch (Exception e) {
                log.warn("向量检索失败，降级为关键词检索: {}", e.getMessage());
            }
        }
        // 向量命中时直接返回，不再执行关键词检索。
        if (!result.isEmpty()) {
            return result;
        }
        // 向量不可用或未命中时降级为关键词打分。
        return keywordSearch(question);
    }

    /**
     * 关键词降级检索：对全部文档做词频打分。
     * <p>标题命中权重为正文的 3 倍；只保留得分大于 0 的文档，按得分倒序
     * <p>得分只用于相对排序，不做阈值过滤。
     * <p>同分时保持数据库返回顺序，不额外引入随机性。
     * <p>标题命中权重为正文的 3 倍，只保留得分大于 0 的文档。
     * <p>向量检索异常或未命中时才会走到这里，属于兜底路径。
     * 取前 MAX_SOURCES 条。数据量较小，因此直接内存扫描，避免引入额外索引。
     *
     * @param question 居民问题
     * <p>只保留得分大于 0 的文档，按得分倒序。
     * @return 关键词命中的来源列表
     */
    private List<AiChatVO.SourceVO> keywordSearch(String question) {
        // 对全部文档做词频打分，适合知识库规模较小的场景。
        // 标题命中权重是正文的 3 倍。
        // 只保留得分大于 0 的文档并取前 MAX_SOURCES 条。
        // 从问题中提取有效词项，包含预置的同义词提示。
        List<String> terms = keywords(question);
        // 降级路径读取全部文档；知识库规模有限，可接受一次性加载。
        List<KnowledgeDoc> docs = knowledgeDocMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>().orderByDesc(KnowledgeDoc::getCreateTime));
        // 逐篇计算得分，保持实现简单直观。
        return docs.stream()
                // 每篇文档的得分 = 正文词频 + 3 倍标题词频。
                .map(doc -> {
                    // 得分只用于相对排序，不做归一化。
                    int score = 0;
                    // 累加所有词项的命中次数。
                    for (String term : terms) {
                        // 正文命中权重为 1。
                        score += count(doc.getContent(), term);
                        // 标题命中权重更高，提升主题相关文档的排序。
                        score += count(doc.getTitle(), term) * 3;
                    }
                    // 保留文档与得分的对应关系，供后续排序与截取。
                    return Map.entry(doc, score);
                })
                // 过滤完全未命中的文档，避免返回不相关内容。
                .filter(entry -> entry.getValue() > 0)
                // 得分倒序；同分时保留数据库查询顺序。
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                // 与向量召回保持相同的来源数量上限。
                .limit(MAX_SOURCES)
                // 关键词命中的片段同样围绕问题截取。
                .map(entry -> new AiChatVO.SourceVO(entry.getKey().getTitle(),
                        snippet(entry.getKey().getContent(), question)))
                .toList();
    }

    /**
     * 从问题中提取关键词。
     * 领域常见词，提升「租赁」「报修」等短问题的召回率。
     * <p>词表是静态配置，不依赖外部服务。
     * <p>常见词只在问题实际包含时加入，避免无差别扩展。
     * <p>词项使用 LinkedHashSet 去重并保持出现顺序。
     * <p>短问题可能切不出词，此时依赖常见词表提供稳定信号。
     * <p>先按中英文标点与空白切分，保留长度至少为 2 的词；再补充一组
     *
     * @param question 居民问题
     * <p>常见词表提供稳定的兜底召回信号。
     * @return 去重后的关键词列表
     */
    private List<String> keywords(String question) {
        // 按中英文标点切分问题，保留长度至少为 2 的词。
        // 补充领域常见词，提升短问题召回率。
        // 使用 LinkedHashSet 去重并保持顺序。
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        // 使用 LinkedHashSet 去重并保持出现顺序。
        Set<String> terms = new LinkedHashSet<>();
        // 按常见标点切分，兼容中英文输入。
        for (String token : question.split("[\\s,，。？?！!；;：:、]+")) {
            // 单字词噪声较大，直接丢弃。
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        // 领域常见词：即使问题很短也能提供稳定召回信号。
        String[] common = {"租赁", "押金", "归还", "逾期", "报修", "维修", "安全", "使用", "预约", "取消", "规则"};
        // 只补充问题中实际出现的词，避免无差别扩展。
        for (String word : common) {
            // 包含即加入，不要求独立成词。
            if (question.contains(word)) {
                terms.add(word);
            }
        }
        // 转为 List 便于后续遍历与计数。
        return new ArrayList<>(terms);
    }

    /**
     * 统计词项在文本中出现的次数（非重叠匹配）。
     *
     * @param text 待统计文本，可为空
     * @param term 词项，可为空
     * <p>非重叠匹配，每次命中后跳过整个词项。
     * @return 出现次数；任一参数为空时返回 0
     */
    private int count(String text, String term) {
        // 非重叠统计词项出现次数。
        // 空文本或空词项直接返回 0。
        // 每次命中后跳过整个词项，避免重复计数。
        // 空文本或空词项不产生任何命中。
        if (!StringUtils.hasText(text) || !StringUtils.hasText(term)) {
            return 0;
        }
        // 命中计数。
        int count = 0;
        // 从文本开头开始查找。
        int index = 0;
        // 每次命中后跳过整个词项，避免同一次出现被重复计算。
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    /**
     * 围绕问题关键词截取正文片段。
     * 总长最多 260 个字符；首尾截断时补省略号。
     * <p>片段长度固定上限，便于前端卡片展示。
     * <p>没有命中关键词时从正文开头截取。
     * <p>片段只用于展示与 prompt，不改变原文存储。
     * <p>先压缩连续空白，再定位第一个命中的关键词，向前保留 80 个字符、
     *
     * @param text     正文
     * @param question 居民问题
     * <p>命中词前保留 80 个字符作为上下文。
     * @return 片段文本；正文为空时返回空串
     */
    private String snippet(String text, String question) {
        // 压缩连续空白，保证片段在单行内展示。
        // 定位第一个命中词，向前保留 80 个字符。
        // 总长度最多 260 个字符，首尾截断时补省略号。
        if (!StringUtils.hasText(text)) {
            return "";
        }
        // 压缩换行与连续空白，保证片段在单行内展示。
        String normalized = text.replaceAll("\\s+", " ").trim();
        // -1 表示没有命中任何关键词，此时从头截取。
        int index = -1;
        // 使用与关键词检索相同的词项，保证片段与命中原因一致。
        for (String term : keywords(question)) {
            // 取第一个出现的词项位置。
            index = normalized.indexOf(term);
            if (index >= 0) {
                break;
            }
        }
        // 命中词前保留 80 个字符作为上下文。
        int start = Math.max(0, index < 0 ? 0 : index - 80);
        // 片段总长不超过 260 个字符。
        int end = Math.min(normalized.length(), start + 260);
        // 只有实际截断时才添加省略号。
        return (start > 0 ? "…" : "") + normalized.substring(start, end)
                + (end < normalized.length() ? "…" : "");
    }

    /**
     * 把文档正文切分为带元数据的向量片段。
     * 与 chunk 序号，供检索结果标注来源与重建索引时清理。
     * <p>chunk 序号用于调试与定位原文位置。
     * <p>元数据 title 为空时检索结果会使用「知识库」占位。
     * <p>切块大小与重叠由共享的 TextChunker 统一配置。
     * <p>每块主键由文档 ID 与块序号确定性生成，元数据包含 docId、title
     *
     * @param doc 知识库文档
     * <p>每块都带有 docId、title 与 chunk 序号元数据。
     * @return 向量片段列表
     */
    private List<Document> split(KnowledgeDoc doc) {
        // 使用共享切块器按固定大小与重叠切分正文。
        // 每块主键由文档 ID 与块序号确定性生成。
        // 元数据包含 docId、title 与 chunk 序号。
        // 按配置的块大小与重叠切分，保持段落语义连续。
        List<String> texts = CHUNKER.split(doc.getContent());
        // 逐块构造带元数据的 Document。
        List<Document> docs = new ArrayList<>();
        // 块序号从 0 开始，参与确定性主键生成。
        for (int i = 0; i < texts.size(); i++) {
            // 主键、正文与元数据一起写入向量库。
            docs.add(new Document(CHUNKER.documentId(doc.getId(), i), texts.get(i), Map.of(
                    "docId", doc.getId(),
                    "title", doc.getTitle(),
                    // chunk 序号可用于调试与定位原文位置。
                    "chunk", i
            )));
        }
        return docs;
    }

    /**
     * 推导某文档全部 chunk 的确定性主键。
     * <p>切块数量由切块器决定，删除时按同一规则生成完整主键集合。
     *
     * @param docId 文档主键
     * <p>与写入时使用同一套确定性规则。
     * @return chunk 主键列表
     */
    private List<String> chunkIds(Long docId) {
        // 与 split 使用同一套主键规则。
        // 删除时按文档 ID 生成完整主键集合。
        // 不依赖文档正文是否仍可读取。
        // 与 documentId 使用同一套规则，保证删除与写入一致。
        return CHUNKER.chunkIds(docId);
    }
}
