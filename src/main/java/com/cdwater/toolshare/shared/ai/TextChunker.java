package com.cdwater.toolshare.shared.ai;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 确定性文本切块：同一文档重复切块得到相同 chunk id，便于幂等替换与清理残留高位 chunk。
 * <p>知识库与工具向量索引共用本类，仅 id 前缀、块大小与上限不同。
 */
public final class TextChunker {

    private final String idPrefix;
    private final int chunkSize;
    private final int overlap;
    private final int maxChunks;

    public TextChunker(String idPrefix, int chunkSize, int overlap, int maxChunks) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，overlap 必须小于 chunkSize");
        }
        this.idPrefix = idPrefix;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").trim();
        List<String> texts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length() && texts.size() < maxChunks) {
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

    /** 覆盖切块上限内的全部下标，内容变短后残留的高位 chunk 也能被清空。 */
    public List<String> chunkIds(Long documentKey) {
        List<String> ids = new ArrayList<>(maxChunks);
        for (int i = 0; i < maxChunks; i++) {
            ids.add(documentId(documentKey, i));
        }
        return ids;
    }

    public String documentId(Long documentKey, int chunk) {
        String raw = idPrefix + "-" + documentKey + "-" + chunk;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
