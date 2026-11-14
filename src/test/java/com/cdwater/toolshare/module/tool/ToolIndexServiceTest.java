package com.cdwater.toolshare.module.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量索引同步：用测试替身验证 upsert/remove 真正落到 VectorStore，
 * 以及外部向量库失败时不向主流程抛异常、留待重试。
 */
class ToolIndexServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> provider(VectorStore store) {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private static Tool tool(long id) {
        Tool tool = new Tool();
        tool.setId(id);
        tool.setName("冲击电钻");
        tool.setCategory("WX");
        tool.setModel("博世 GSB 550");
        tool.setDeposit(new BigDecimal("200.00"));
        tool.setStorageArea("A 区");
        tool.setDamageLevel("NEW");
        tool.setAuditStatus(1);
        tool.setStatus("AVAILABLE");
        tool.setUsageGuide("<p>先断电再换钻头</p>");
        return tool;
    }

    @Test
    void upsert_replaces_old_chunks_with_new_documents() {
        VectorStore store = mock(VectorStore.class);
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        service.upsert(tool(42L));

        verify(store).delete(anyList());
        verify(store).add(anyList());
    }

    @Test
    void remove_deletes_chunk_ids_without_adding_documents() {
        VectorStore store = mock(VectorStore.class);
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        service.remove(42L);

        verify(store).delete(anyList());
        verify(store, never()).add(anyList());
    }

    @Test
    void vector_store_failure_does_not_block_main_flow_and_is_retried() {
        VectorStore store = mock(VectorStore.class);
        doThrow(new IllegalStateException("qdrant down")).doNothing().when(store).add(anyList());
        ToolMapper toolMapper = mock(ToolMapper.class);
        when(toolMapper.selectById(42L)).thenReturn(tool(42L));
        ToolIndexService service = new ToolIndexService(toolMapper,
                provider(store), new ObjectMapper());

        assertDoesNotThrow(() -> service.upsert(tool(42L)));

        service.retryFailed();

        verify(store, times(2)).add(anyList());
    }

    @Test
    void missing_vector_store_is_a_no_op() {
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(null), new ObjectMapper());

        assertDoesNotThrow(() -> service.upsert(tool(42L)));
        assertDoesNotThrow(() -> service.remove(42L));
    }

    @Test
    void documents_carry_tool_metadata_for_recommendation_filtering() {
        VectorStore store = mock(VectorStore.class);
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        service.upsert(tool(42L));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Document>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).add(captor.capture());
        Map<String, Object> metadata = captor.getValue().getFirst().getMetadata();
        org.junit.jupiter.api.Assertions.assertEquals("tool", metadata.get("kind"));
        org.junit.jupiter.api.Assertions.assertEquals(42L, metadata.get("toolId"));
    }
}
