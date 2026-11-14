package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.Dict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具向量索引：把可用于推荐的工具文本写入 Qdrant。
 * <p>索引 ID 使用 {@code tool-{id}-{chunk}} 派生，与知识库的 {@code kb-} 前缀隔离；
 * 索引内容只包含静态属性，是否可租在查询时由数据库过滤；报废/恢复/审核等状态变更仍同步删除或重建索引。
 * 外部向量库失败只记日志并进入重试队列，不影响工具主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolIndexService {

    private static final int CHUNK_SIZE = 700;
    private static final int CHUNK_OVERLAP = 80;
    private static final int MAX_CHUNKS = 20;

    private final ToolMapper toolMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectMapper objectMapper;

    /** 索引失败的工具 id，定时任务按数据库当前状态重试 */
    private final Set<Long> retryQueue = ConcurrentHashMap.newKeySet();

    @Value("${toolshare.ai.recommend.index-on-startup:true}")
    private boolean indexOnStartup = true;

    /** 工具新增/编辑后调用：先清掉旧 chunk，再写入新内容 */
    public void upsert(Tool tool) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || tool == null || tool.getId() == null) {
            return;
        }
        try {
            vectorStore.delete(chunkIds(tool.getId()));
            List<Document> docs = buildDocs(tool);
            if (!docs.isEmpty()) {
                vectorStore.add(docs);
            }
            retryQueue.remove(tool.getId());
        } catch (Exception e) {
            retryQueue.add(tool.getId());
            log.warn("工具向量索引失败，已加入重试队列 toolId={}: {}", tool.getId(), e.getMessage());
        }
    }

    public void remove(Long toolId) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || toolId == null) {
            return;
        }
        try {
            vectorStore.delete(chunkIds(toolId));
            retryQueue.remove(toolId);
        } catch (Exception e) {
            retryQueue.add(toolId);
            log.warn("删除工具向量索引失败，已加入重试队列 toolId={}: {}", toolId, e.getMessage());
        }
    }

    /** 重试失败条目：以数据库当前状态为准，报废/未审核的删除，其余重建。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void retryFailed() {
        if (retryQueue.isEmpty() || vectorStoreProvider.getIfAvailable() == null) {
            return;
        }
        for (Long toolId : List.copyOf(retryQueue)) {
            Tool tool = toolMapper.selectById(toolId);
            boolean indexable = tool != null
                    && !ToolStatus.SCRAPPED.code().equals(tool.getStatus())
                    && tool.getAuditStatus() != null
                    && tool.getAuditStatus() == AuditStatus.APPROVED.code();
            if (indexable) {
                upsert(tool);
            } else {
                remove(toolId);
            }
        }
    }

    /**
     * 启动后全量重建（幂等）。Qdrant / embedding 不可用时逐条记录并跳过，
     * 下次启动会自动重试，不需要在工具表里维护索引状态字段。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAll() {
        if (!indexOnStartup) {
            return;
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return;
        }
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .ne(Tool::getStatus, ToolStatus.SCRAPPED.code())
                .eq(Tool::getAuditStatus, AuditStatus.APPROVED.code()));
        for (Tool tool : tools) {
            upsert(tool);
        }
        log.info("工具向量索引重建完成，共 {} 个工具", tools.size());
    }

    private List<Document> buildDocs(Tool tool) {
        String text = """
                【工具】%s
                类别：%s
                型号：%s
                押金：%s 元
                存放位置：%s
                损坏程度：%s
                使用说明：%s
                """.formatted(
                nullToEmpty(tool.getName()),
                Dict.label(Dict.TOOL_CATEGORY, tool.getCategory()),
                nullToEmpty(tool.getModel()),
                tool.getDeposit(),
                nullToEmpty(tool.getStorageArea()),
                Dict.label(Dict.DAMAGE_LEVEL, tool.getDamageLevel()),
                plainText(tool.getUsageGuide()));
        List<String> chunks = chunkTexts(text);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(new Document(documentId(tool.getId(), i), chunks.get(i), Map.of(
                    "kind", "tool",
                    "toolId", tool.getId(),
                    "name", nullToEmpty(tool.getName()),
                    "category", nullToEmpty(tool.getCategory())
            )));
        }
        return docs;
    }

    /** 使用说明可能是 HTML，也可能是前端保存的 JSON 步骤数组 */
    private String plainText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            try {
                JsonNode root = objectMapper.readTree(value);
                if (root != null && root.isArray()) {
                    StringBuilder builder = new StringBuilder();
                    for (JsonNode node : root) {
                        String step = node.path("text").asText("");
                        if (StringUtils.hasText(step)) {
                            builder.append(step.trim()).append(' ');
                        }
                    }
                    if (!builder.isEmpty()) {
                        return builder.toString().trim();
                    }
                }
            } catch (Exception ignored) {
                // 解析失败按普通文本处理
            }
        }
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private List<String> chunkTexts(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").trim();
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

    private List<String> chunkIds(Long toolId) {
        List<String> ids = new ArrayList<>(MAX_CHUNKS);
        for (int i = 0; i < MAX_CHUNKS; i++) {
            ids.add(documentId(toolId, i));
        }
        return ids;
    }

    private String documentId(Long toolId, int chunk) {
        return UUID.nameUUIDFromBytes(("tool-" + toolId + "-" + chunk).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
