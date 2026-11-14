package com.cdwater.toolshare.shared.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享切块器：知识库与工具索引使用同一规则，chunk id 必须确定且可覆盖上限内全部下标。
 */
class TextChunkerTest {

    @Test
    void splits_with_overlap_and_keeps_all_content() {
        TextChunker chunker = new TextChunker("t", 10, 2, 5);
        String content = "abcdefghijklmnopqrstuvwxyz";

        List<String> chunks = chunker.split(content);

        assertEquals(List.of("abcdefghij", "ijklmnopqr", "qrstuvwxyz"), chunks);
    }

    @Test
    void honors_max_chunks() {
        TextChunker chunker = new TextChunker("t", 10, 2, 3);
        assertEquals(3, chunker.split("a".repeat(100)).size());
    }

    @Test
    void chunk_ids_are_deterministic_and_cover_max_range() {
        TextChunker chunker = new TextChunker("kb", 10, 2, 4);

        assertEquals(4, chunker.chunkIds(7L).size());
        assertEquals(chunker.documentId(7L, 0), chunker.documentId(7L, 0));
        assertNotEquals(chunker.documentId(7L, 0), chunker.documentId(7L, 1));
        assertNotEquals(chunker.documentId(7L, 0), chunker.documentId(8L, 0));
    }

    @Test
    void blank_content_produces_no_chunks() {
        assertTrue(new TextChunker("t", 10, 2, 5).split("  \n ").isEmpty());
    }
}
