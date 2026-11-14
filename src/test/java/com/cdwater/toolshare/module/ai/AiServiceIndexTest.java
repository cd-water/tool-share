package com.cdwater.toolshare.module.ai;

import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库向量索引：用内存替身验证切块主键确定性、重复索引幂等、删除后不可再检索。
 */
class AiServiceIndexTest {

    private static AiService service(VectorStore store) {
        return new AiService(mock(KnowledgeDocMapper.class), provider(store), provider(null));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static KnowledgeDoc doc(long id, String content) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setTitle("租赁规则");
        doc.setContent(content);
        return doc;
    }

    @Test
    void index_uses_deterministic_chunk_ids_and_is_idempotent() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        AiService service = service(store);
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));

        assertTrue(service.index(doc));
        assertEquals(3, store.documents().size());
        assertEquals("57fdc175-9427-3e8b-93e5-129340c438b4", store.documents().get(0).getId());
        assertEquals("446b0f2e-d816-3ef1-9c2e-2fd4f55c590b", store.documents().get(1).getId());
        assertEquals("384b8f0b-94d8-3f67-921f-092290e9c507", store.documents().get(2).getId());

        assertTrue(service.index(doc));
        assertEquals(3, store.documents().size(), "重复索引应替换而不是追加 chunk");
    }

    @Test
    void reindex_after_content_shrinks_removes_stale_chunks() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        AiService service = service(store);
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));
        service.index(doc);

        doc.setContent("更新后的短内容");
        assertTrue(service.index(doc));

        assertEquals(1, store.documents().size());
        assertEquals("57fdc175-9427-3e8b-93e5-129340c438b4", store.documents().get(0).getId());
    }

    @Test
    void index_failure_returns_false_instead_of_throwing() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.failAdd = true;

        assertFalse(service(store).index(doc(7L, "内容")));
    }

    @Test
    void remove_index_clears_every_chunk_so_it_cannot_be_retrieved() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        AiService service = service(store);
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));
        service.index(doc);

        assertTrue(service.removeIndex(7L));

        assertTrue(store.documents().isEmpty());
        assertTrue(service.retrieve("租赁").isEmpty());
    }

    @Test
    void remove_index_without_vector_store_is_a_no_op_success() {
        AiService service = service(null);

        assertTrue(service.removeIndex(7L));
        assertFalse(service.index(doc(7L, "内容")));
    }

    /** 内存向量库替身：只保留 id -> Document，检索直接返回全部内容。 */
    private static final class InMemoryVectorStore implements VectorStore {

        private final Map<String, Document> store = new LinkedHashMap<>();
        private boolean failAdd;

        @Override
        public void add(List<Document> documents) {
            if (failAdd) {
                throw new IllegalStateException("embedding service down");
            }
            for (Document document : documents) {
                store.put(document.getId(), document);
            }
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return new ArrayList<>(store.values());
        }

        List<Document> documents() {
            return new ArrayList<>(store.values());
        }
    }
}
