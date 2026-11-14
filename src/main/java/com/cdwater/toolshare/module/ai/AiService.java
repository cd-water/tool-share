package com.cdwater.toolshare.module.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import com.cdwater.toolshare.module.ai.AiChatVO;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private static final int CHUNK_SIZE = 700;
    private static final int CHUNK_OVERLAP = 80;
    private static final int MAX_CHUNKS = 60;

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
        String answer = generateAnswer(question, sources);
        if (!StringUtils.hasText(answer)) {
            answer = fallbackAnswer(sources);
        }
        AiChatVO vo = new AiChatVO();
        vo.setAnswer(answer);
        vo.setSources(sources);
        return vo;
    }

    /** 检索知识库来源：向量优先，异常或为空时降级为关键词检索。 */
    public List<AiChatVO.SourceVO> retrieve(String question) {
        return search(question);
    }

    /** 流式生成回答；外部 AI 不可用或异常时流式输出兜底文案，保证事件流完整结束。 */
    public Flux<String> streamAnswer(String question, List<AiChatVO.SourceVO> sources) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return Flux.just(fallbackAnswer(sources));
        }
        String context = buildContext(sources);
        return builder.build().prompt()
                .system(SYSTEM_PROMPT)
                .user("知识库内容：\n" + context + "\n\n居民问题：" + question)
                .stream()
                .content()
                .filter(StringUtils::hasText)
                .onErrorResume(e -> {
                    log.warn("AI 流式生成失败，降级为知识库检索: {}", e.getMessage());
                    return Flux.just(fallbackAnswer(sources));
                });
    }

    private String generateAnswer(String question, List<AiChatVO.SourceVO> sources) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null || sources.isEmpty()) {
            return null;
        }
        try {
            return builder.build().prompt()
                    .system(SYSTEM_PROMPT)
                    .user("知识库内容：\n" + buildContext(sources) + "\n\n居民问题：" + question)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("AI 生成回答失败，降级为知识库检索: {}", e.getMessage());
            return null;
        }
    }

    private String fallbackAnswer(List<AiChatVO.SourceVO> sources) {
        if (sources.isEmpty()) {
            return "暂未在知识库中找到相关说明。建议您联系社区服务站工作人员，或换个关键词再试。";
        }
        return "根据社区工具知识库，为您找到以下相关信息：\n" + buildContext(sources)
                + "\n如需更准确的答复，请联系社区服务站工作人员。";
    }

    private String buildContext(List<AiChatVO.SourceVO> sources) {
        return sources.stream()
                .map(item -> "\n【" + item.getTitle() + "】\n" + item.getSnippet())
                .reduce("", String::concat);
    }

    private static final String SYSTEM_PROMPT = "你是社区共享工具租赁系统的服务助手。请只依据提供的知识库内容，"
            + "用简洁、友好的中文回答居民问题；如果知识库没有答案，请明确说明并建议联系社区工作人员。"
            + "回答中可以分点说明，但不要编造规则。";

    private List<AiChatVO.SourceVO> search(String question) {
        List<AiChatVO.SourceVO> result = new ArrayList<>();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            try {
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(question)
                        .topK(4)
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
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(4)
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
        List<String> texts = chunkTexts(doc.getContent());
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            docs.add(new Document(documentId(doc.getId(), i), texts.get(i), Map.of(
                    "docId", doc.getId(),
                    "title", doc.getTitle(),
                    "chunk", i
            )));
        }
        return docs;
    }

    /**
     * 与 split 使用同一套确定性主键规则（{@code kb-{docId}-{chunk}}），覆盖切块上限内的全部下标，
     * 保证内容变短后残留的高位 chunk 也能被清空。
     */
    private List<String> chunkIds(Long docId) {
        List<String> ids = new ArrayList<>(MAX_CHUNKS);
        for (int i = 0; i < MAX_CHUNKS; i++) {
            ids.add(documentId(docId, i));
        }
        return ids;
    }

    private String documentId(Long docId, int chunk) {
        return UUID.nameUUIDFromBytes(("kb-" + docId + "-" + chunk).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private List<String> chunkTexts(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.replaceAll("\\r\\n", "\n").trim();
        List<String> texts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length() && texts.size() < MAX_CHUNKS) {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            String chunk = normalized.substring(start, end);
            if (StringUtils.hasText(chunk)) {
                texts.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return texts;
    }
}
