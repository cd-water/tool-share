package com.cdwater.toolshare.shared.ai;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 确定性文本切块：同一文档重复切块得到相同 chunk id，便于幂等替换与清理残留高位 chunk。
 * <p>知识库与工具向量索引共用本类，仅 id 前缀、块大小与上限不同。
 *
 * <p>切块算法按固定窗口滑动，相邻块保留 {@code overlap} 个字符的重叠，
 * 避免语义刚好落在边界时被割裂。所有参数在构造时校验，非法配置
 * 立即失败，而不是在运行期产生不可预期的块。</p>
 *
 * <p>chunk id 由“前缀 + 文档键 + 下标”经 UUID v3 名称哈希生成，因此
 * 不依赖随机数或时间：同一输入永远得到同一 id。内容变短后，旧的高位
 * 块 id 仍可通过 {@link #chunkIds(Long)} 枚举并删除。</p>
 */
public final class TextChunker {

    /** id 前缀，用于区分知识库与工具向量两个索引空间。 */
    private final String idPrefix;

    /** 每个块的最大字符数。 */
    private final int chunkSize;

    /** 相邻块的重叠字符数，必须小于 chunkSize。 */
    private final int overlap;

    /** 单文档最多切出的块数，用于限制索引规模与清理范围。 */
    private final int maxChunks;

    /**
     * 构造切块器。
     *
     * @param idPrefix  块 id 前缀
     * @param chunkSize 每块最大字符数，必须大于 0
     * @param overlap   相邻块重叠字符数，必须小于 chunkSize
     * @param maxChunks 单文档最大块数
     * @throws IllegalArgumentException 参数不合法时抛出
     */
    public TextChunker(String idPrefix, int chunkSize, int overlap, int maxChunks) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            // 重叠大于等于块大小会导致游标无法前进。
            throw new IllegalArgumentException("chunkSize 必须大于 0，overlap 必须小于 chunkSize");
        }
        this.idPrefix = idPrefix;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    /**
     * 把文本切分为若干块。
     *
     * <p>先统一换行符并去除首尾空白，再按窗口滑动。空白块会被跳过，
     * 但下标仍按实际窗口计算，保证 id 与内容位置对应。达到 maxChunks
     * 后立即停止，避免超长文档产生无限块。</p>
     *
     * @param content 原始文本
     * @return 文本块列表；内容为空时返回空列表
     */
    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        // 统一换行符，避免同一文档因平台差异产生不同切块。
        String normalized = content.replace("\r\n", "\n").trim();
        List<String> texts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length() && texts.size() < maxChunks) {
            // 窗口末尾不超过文本长度。
            int end = Math.min(normalized.length(), start + chunkSize);
            String chunk = normalized.substring(start, end);
            if (StringUtils.hasText(chunk)) {
                texts.add(chunk);
            }
            if (end >= normalized.length()) {
                // 已到文本末尾，结束循环。
                break;
            }
            // 回退 overlap 个字符形成重叠；至少前进 1，防止死循环。
            start = Math.max(end - overlap, start + 1);
        }
        return texts;
    }

    /** 覆盖切块上限内的全部下标，内容变短后残留的高位 chunk 也能被清空。 */
    public List<String> chunkIds(Long documentKey) {
        // 固定返回 maxChunks 个 id，即使当前文本块更少。
        List<String> ids = new ArrayList<>(maxChunks);
        for (int i = 0; i < maxChunks; i++) {
            ids.add(documentId(documentKey, i));
        }
        return ids;
    }

    /**
     * 生成指定文档与下标的确定性 chunk id。
     *
     * <p>使用 UUID v3（名称哈希）而不是随机 UUID，保证同一文档重复索引
     * 时覆盖同一批向量，而不是不断新增。</p>
     *
     * @param documentKey 文档主键
     * @param chunk       块下标，从 0 开始
     * @return 稳定的 UUID 字符串
     */
    public String documentId(Long documentKey, int chunk) {
        String raw = idPrefix + "-" + documentKey + "-" + chunk;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
