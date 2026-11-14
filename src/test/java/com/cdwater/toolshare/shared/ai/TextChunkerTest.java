package com.cdwater.toolshare.shared.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本切块器单元测试。
 * <p>
 * 测试范围：知识库文档与工具索引共用的切块规则、重叠窗口与块 ID 生成。
 * 依赖环境：纯工具类，无 AI 服务与向量库依赖。
 * 关键不变量：切块保留重叠上下文且不丢内容；块数不超过上限；
 * 同一文档下标生成的 ID 确定且可区分不同文档；空白内容不产生块。
 */
class TextChunkerTest {

    /**
     * 场景：将 26 个字母按窗口 10、重叠 2 切块。
     * 前置条件：输入长度超过一个窗口。
     * 断言目标：得到三段带重叠的连续文本，首尾内容完整保留不丢失。
     */
    @Test
    void splits_with_overlap_and_keeps_all_content() {
        // 构造器参数依次为：集合/索引前缀、窗口长度、重叠字符数、最大块数。
        TextChunker chunker = new TextChunker("t", 10, 2, 5);
        // 26 个字母便于目测切块边界与重叠区是否正确，无需额外注释说明内容。
        String content = "abcdefghijklmnopqrstuvwxyz";

        // 执行切块；窗口每次前进 8 个字符（10 - 2）。
        List<String> chunks = chunker.split(content);

        // 三块首尾相接且相邻两块共享 2 个字符，内容无丢失。
        // 期望结果直接写死，防止实现改动悄悄改变切块边界。
        // 第三块从下标 16 开始，覆盖到结尾。
        assertEquals(List.of("abcdefghij", "ijklmnopqr", "qrstuvwxyz"), chunks);
    }

    /**
     * 场景：长文本的切块数量超过配置上限。
     * 前置条件：maxChunks 设置为 3。
     * 断言目标：最多返回 3 块，防止单文档产生无限索引条目。
     */
    @Test
    void honors_max_chunks() {
        // 将最大块数压到 3，输入 100 个字符足以触发截断。
        TextChunker chunker = new TextChunker("t", 10, 2, 3);
        // 超长文档只保留前 3 块，控制向量库写入量与检索成本。
        // 断言只关心数量上限，不关心具体块内容。
        assertEquals(3, chunker.split("a".repeat(100)).size());
    }

    /**
     * 场景：为同一文档请求块 ID，并比较不同下标与不同文档。
     * 前置条件：documentId 由文档 ID 与块下标共同决定。
     * 断言目标：块数等于上限、同输入 ID 稳定、不同下标/文档的 ID 互不相同，
     * 保证重复索引可覆盖而不会误删其他文档的块。
     */
    @Test
    void chunk_ids_are_deterministic_and_cover_max_range() {
        // 知识库前缀 kb，块上限 4，用于验证 ID 生成的稳定范围。
        TextChunker chunker = new TextChunker("kb", 10, 2, 4);

        // 块 ID 数量与最大块数一致，供批量写入向量库使用。
        assertEquals(4, chunker.chunkIds(7L).size());
        // 同一文档同一块下标必须生成相同 ID，重复索引才能覆盖旧记录。
        assertEquals(chunker.documentId(7L, 0), chunker.documentId(7L, 0));
        // 不同块的 ID 必须不同，否则后写会覆盖前一块。
        // 以下断言覆盖「可重复」与「可区分」两个维度。
        assertNotEquals(chunker.documentId(7L, 0), chunker.documentId(7L, 1));
        // 不同文档的同名块也不能碰撞，避免删除时误伤其他文档。
        assertNotEquals(chunker.documentId(7L, 0), chunker.documentId(8L, 0));
    }

    /**
     * 场景：输入仅含空格与换行。
     * 前置条件：空白内容不构成有效知识。
     * 断言目标：返回空列表，不写入无语义的向量块。
     */
    @Test
    void blank_content_produces_no_chunks() {
        // 仅空格与换行没有任何可检索语义，返回空块列表。
        // 空输入也不应产生占位块。
        assertTrue(new TextChunker("t", 10, 2, 5).split("  \n ").isEmpty());
    }
}
