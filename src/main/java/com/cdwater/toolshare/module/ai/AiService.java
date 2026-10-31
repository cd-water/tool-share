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

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    public boolean index(KnowledgeDoc doc) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || !StringUtils.hasText(doc.getContent())) {
            return false;
        }
        try {
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

    public void removeIndex(KnowledgeDoc doc) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || doc == null || doc.getId() == null) {
            return;
        }
        List<String> ids = chunkIds(doc);
        if (ids.isEmpty()) {
            return;
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.warn("删除向量索引失败: {}", e.getMessage());
        }
    }

    public AiChatVO chat(String question) {
        List<AiChatVO.SourceVO> sources = search(question);
        String context = sources.stream()
                .map(item -> "《" + item.getTitle() + "》\n" + item.getSnippet())
                .reduce("", (a, b) -> a + "\n\n" + b);
        String answer = null;
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder != null && !sources.isEmpty()) {
            try {
                answer = builder.build().prompt()
                        .system("你是社区共享工具租赁系统的服务助手。请只依据提供的知识库内容，"
                                + "用简洁、友好的中文回答居民问题；如果知识库没有答案，请明确说明并建议联系社区工作人员。"
                                + "回答中可以分点说明，但不要编造规则。")
                        .user("知识库内容：\n" + context + "\n\n居民问题：" + question)
                        .call()
                        .content();
            } catch (Exception e) {
                log.warn("AI 生成回答失败，降级为知识库检索: {}", e.getMessage());
            }
        }
        if (!StringUtils.hasText(answer)) {
            if (sources.isEmpty()) {
                answer = "暂未在知识库中找到相关说明。建议您联系社区服务站工作人员，或换个关键词再试。";
            } else {
                StringBuilder sb = new StringBuilder("根据社区工具知识库，为您找到以下相关信息：\n");
                for (AiChatVO.SourceVO source : sources) {
                    sb.append("\n【").append(source.getTitle()).append("】\n")
                            .append(source.getSnippet()).append("\n");
                }
                sb.append("\n如需更准确的答复，请联系社区服务站工作人员。");
                answer = sb.toString();
            }
        }
        AiChatVO vo = new AiChatVO();
        vo.setAnswer(answer);
        vo.setSources(sources);
        return vo;
    }

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

    /** 与 split 使用同一套规则派生确定性向量主键，保证删除时能按文档清空对应 chunk。 */
    private List<String> chunkIds(KnowledgeDoc doc) {
        List<String> texts = chunkTexts(doc.getContent());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            ids.add(documentId(doc.getId(), i));
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
        int chunkSize = 700;
        int overlap = 80;
        int start = 0;
        while (start < normalized.length() && texts.size() < 60) {
            int end = Math.min(normalized.length(), start + chunkSize);
            String chunk = normalized.substring(start, end);
            if (StringUtils.hasText(chunk)) {
                texts.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return texts;
    }
}
