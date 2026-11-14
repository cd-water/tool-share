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

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private static final TextChunker CHUNKER = new TextChunker("kb", 700, 80, 60);
    /** 单次问答最多召回的知识片段数，避免把整库内容拼进 prompt。 */
    private static final int MAX_SOURCES = 4;
    /** 拼进 prompt 的知识库上下文上限（字符）。 */
    private static final int MAX_CONTEXT_CHARS = 1600;
    /** 单次大模型调用超时，超时后降级为知识库兜底回答。 */
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern CITATION = Pattern.compile("\\[\\d+]");

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    /**
     * 重建文档索引：先清空该文档的全部旧 chunk，再写入当前内容。
     * 重复执行幂等；外部向量库失败返回 false，由调用方保留 indexed=0 等待重试。
     */
    public boolean index(KnowledgeDoc doc) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || doc == null || doc.getId() == null || !StringUtils.hasText(doc.getContent())) {
            return false;
        }
        try {
            vectorStore.delete(chunkIds(doc.getId()));
            List<Document> chunks = split(doc);
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
            }
            return true;
        } catch (Exception e) {
            log.warn("知识库向量索引失败（不影响文档保存）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清空文档的全部向量 chunk。按 id 推导固定范围的确定性主键，不依赖文档内容是否仍在数据库。
     *
     * @return 是否已清理；未配置向量库时视为无需清理
     */
    public boolean removeIndex(Long docId) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || docId == null) {
            return true;
        }
        try {
            vectorStore.delete(chunkIds(docId));
            return true;
        } catch (Exception e) {
            log.warn("删除向量索引失败 docId={}: {}", docId, e.getMessage());
            return false;
        }
    }

    public AiChatVO chat(String question) {
        List<AiChatVO.SourceVO> sources = retrieve(question);
        String answer = withCitations(generateAnswer(question, sources), sources);
        AiChatVO vo = new AiChatVO();
        vo.setAnswer(answer);
        vo.setSources(sources);
        return vo;
    }

    /** 检索知识库来源：向量优先，异常或为空时降级为关键词检索。 */
    public List<AiChatVO.SourceVO> retrieve(String question) {
        return search(question);
    }

    /**
     * 流式生成回答；外部 AI 不可用或异常时流式输出兜底文案，保证事件流完整结束。
     * 模型未用 [序号] 标注来源或没有返回内容时，追加「参考来源」区块并随流输出。
     */
    public Flux<String> streamAnswer(String question, List<AiChatVO.SourceVO> sources) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return Flux.just(fallbackAnswer(sources));
        }
        String userPrompt = userPrompt(question, sources);
        return Flux.defer(() -> {
            StringBuilder answer = new StringBuilder();
            Flux<String> generated = Flux.defer(() -> builder.build().prompt()
                            .system(SYSTEM_PROMPT)
                            .user(userPrompt)
                            .stream()
                            .content())
                    .filter(StringUtils::hasText)
                    .timeout(LLM_TIMEOUT)
                    .doOnNext(answer::append)
                    .onErrorResume(e -> {
                        log.warn("AI 流式生成失败，降级为知识库检索: {}", e.getMessage());
                        return Flux.empty();
                    });
            return generated.concatWith(Flux.defer(() -> {
                String tail = citationTail(answer.toString(), sources);
                return StringUtils.hasText(tail) ? Flux.just(tail) : Flux.empty();
            }));
        });
    }

    private String generateAnswer(String question, List<AiChatVO.SourceVO> sources) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return null;
        }
        String userPrompt = userPrompt(question, sources);
        try {
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

    private String withCitations(String answer, List<AiChatVO.SourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return fallbackAnswer(sources);
        }
        String tail = citationTail(answer, sources);
        return tail == null ? answer : answer + tail;
    }

    /** 模型已标注来源时返回 null，否则返回要追加的参考来源区块。 */
    private String citationTail(String answer, List<AiChatVO.SourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return fallbackAnswer(sources);
        }
        return CITATION.matcher(answer).find() ? null : sourceBlock(sources);
    }

    private String fallbackAnswer(List<AiChatVO.SourceVO> sources) {
        if (sources.isEmpty()) {
            return "暂未在知识库中找到相关说明。建议您联系社区服务站工作人员，或换个关键词再试。";
        }
        return "根据社区工具知识库，为您找到以下相关信息：" + sourceBlock(sources)
                + "\n如需更准确的答复，请联系社区服务站工作人员。";
    }

    private String sourceBlock(List<AiChatVO.SourceVO> sources) {
        StringBuilder block = new StringBuilder("\n\n参考来源：");
        for (int i = 0; i < sources.size(); i++) {
            AiChatVO.SourceVO source = sources.get(i);
            block.append("\n[").append(i + 1).append("] ").append(source.getTitle())
                    .append("：").append(source.getSnippet());
        }
        return block.toString();
    }

    private String userPrompt(String question, List<AiChatVO.SourceVO> sources) {
        return "知识库内容：\n" + buildContext(sources) + "\n\n居民问题：" + question;
    }

    /** 按来源编号拼接上下文并截断，避免超长 prompt 与成本失控。 */
    private String buildContext(List<AiChatVO.SourceVO> sources) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < sources.size() && context.length() < MAX_CONTEXT_CHARS; i++) {
            AiChatVO.SourceVO source = sources.get(i);
            context.append("\n[").append(i + 1).append("] 【").append(source.getTitle()).append("】\n")
                    .append(source.getSnippet());
        }
        return context.length() > MAX_CONTEXT_CHARS
                ? context.substring(0, MAX_CONTEXT_CHARS) : context.toString();
    }

    private static final String SYSTEM_PROMPT = "你是社区共享工具租赁系统的服务助手。请只依据提供的知识库内容，"
            + "用简洁、友好的中文回答居民问题；如果知识库没有答案，请明确说明并建议联系社区工作人员。"
            + "回答中可以分点说明，但不要编造规则。"
            + "请在回答中用 [1]、[2] 这样的编号标注所依据的来源，编号与知识库内容前的编号一致。";

    private List<AiChatVO.SourceVO> search(String question) {
        List<AiChatVO.SourceVO> result = new ArrayList<>();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            try {
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(question)
                        .topK(MAX_SOURCES)
                        .similarityThresholdAll()
                        .build());
                if (docs != null) {
                    for (Document doc : docs) {
                        Map<String, Object> metadata = doc.getMetadata();
                        String title = metadata.get("title") == null
                                ? "知识库" : String.valueOf(metadata.get("title"));
                        result.add(new AiChatVO.SourceVO(title, snippet(doc.getText(), question)));
                    }
                }
            } catch (Exception e) {
                log.warn("向量检索失败，降级为关键词检索: {}", e.getMessage());
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        return keywordSearch(question);
    }

    private List<AiChatVO.SourceVO> keywordSearch(String question) {
        List<String> terms = keywords(question);
        List<KnowledgeDoc> docs = knowledgeDocMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>().orderByDesc(KnowledgeDoc::getCreateTime));
        return docs.stream()
                .map(doc -> {
                    int score = 0;
                    for (String term : terms) {
                        score += count(doc.getContent(), term);
                        score += count(doc.getTitle(), term) * 3;
                    }
                    return Map.entry(doc, score);
                })
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_SOURCES)
                .map(entry -> new AiChatVO.SourceVO(entry.getKey().getTitle(),
                        snippet(entry.getKey().getContent(), question)))
                .toList();
    }

    private List<String> keywords(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : question.split("[\\s,，。？?！!；;：:、]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        String[] common = {"租赁", "押金", "归还", "逾期", "报修", "维修", "安全", "使用", "预约", "取消", "规则"};
        for (String word : common) {
            if (question.contains(word)) {
                terms.add(word);
            }
        }
        return new ArrayList<>(terms);
    }

    private int count(String text, String term) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(term)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private String snippet(String text, String question) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int index = -1;
        for (String term : keywords(question)) {
            index = normalized.indexOf(term);
            if (index >= 0) {
                break;
            }
        }
        int start = Math.max(0, index < 0 ? 0 : index - 80);
        int end = Math.min(normalized.length(), start + 260);
        return (start > 0 ? "…" : "") + normalized.substring(start, end)
                + (end < normalized.length() ? "…" : "");
    }

    private List<Document> split(KnowledgeDoc doc) {
        List<String> texts = CHUNKER.split(doc.getContent());
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            docs.add(new Document(CHUNKER.documentId(doc.getId(), i), texts.get(i), Map.of(
                    "docId", doc.getId(),
                    "title", doc.getTitle(),
                    "chunk", i
            )));
        }
        return docs;
    }

    private List<String> chunkIds(Long docId) {
        return CHUNKER.chunkIds(docId);
    }
}
