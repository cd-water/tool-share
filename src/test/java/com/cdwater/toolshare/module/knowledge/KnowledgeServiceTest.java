package com.cdwater.toolshare.module.knowledge;

import com.cdwater.toolshare.module.ai.AiService;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库索引一致性：上传成功可追踪、失败保留文档并重试、删除前先清空向量。
 */
class KnowledgeServiceTest {

    private final KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
    private final AiService aiService = mock(AiService.class);
    private final KnowledgeDocVoMapper voMapper = Mappers.getMapper(KnowledgeDocVoMapper.class);
    private final KnowledgeService service = new KnowledgeService(mapper, aiService, voMapper);

    private static MockMultipartFile textFile() {
        return new MockMultipartFile("file", "rules.txt", "text/plain",
                "社区共享工具租赁规则".getBytes(StandardCharsets.UTF_8));
    }

    /** 模拟 MyBatis 自增主键回填。 */
    @BeforeEach
    void assignGeneratedId() {
        doAnswer(invocation -> {
            KnowledgeDoc inserted = invocation.getArgument(0);
            inserted.setId(9L);
            return 1;
        }).when(mapper).insert(any(KnowledgeDoc.class));
    }

    private static KnowledgeDoc pendingDoc(long id) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setTitle("租赁规则");
        doc.setFileName("rules.txt");
        doc.setContent("社区共享工具租赁规则");
        doc.setIndexed(false);
        return doc;
    }

    @Test
    void upload_persists_then_marks_indexed_when_vector_index_succeeds() {
        when(aiService.index(any())).thenReturn(true);

        KnowledgeDocVO vo = service.upload(textFile(), "租赁规则");

        ArgumentCaptor<KnowledgeDoc> inserted = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("社区共享工具租赁规则", inserted.getValue().getContent());

        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).updateById(updated.capture());
        assertTrue(updated.getValue().getIndexed());
        assertTrue(vo.getIndexed());
    }

    @Test
    void upload_keeps_document_with_indexed_false_when_indexing_fails() {
        when(aiService.index(any())).thenReturn(false);

        KnowledgeDocVO vo = service.upload(textFile(), null);

        verify(mapper).insert(any(KnowledgeDoc.class));
        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).updateById(updated.capture());
        assertFalse(updated.getValue().getIndexed());
        assertFalse(vo.getIndexed());
    }

    @Test
    void startup_backfill_indexes_pending_documents_and_updates_status() {
        KnowledgeDoc doc = pendingDoc(9L);
        when(mapper.selectList(any())).thenReturn(List.of(doc));
        when(aiService.index(doc)).thenReturn(false, true);

        service.indexPendingDocs();
        verify(mapper, never()).updateById(any(KnowledgeDoc.class));

        service.retryPendingDocs();

        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).updateById(updated.capture());
        assertTrue(updated.getValue().getIndexed());
    }

    @Test
    void failed_indexing_is_retried_a_bounded_number_of_times() {
        KnowledgeDoc doc = pendingDoc(9L);
        when(mapper.selectList(any())).thenReturn(List.of(doc));
        when(aiService.index(doc)).thenReturn(false);

        service.indexPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();

        verify(aiService, times(3)).index(doc);
    }

    @Test
    void delete_clears_vector_chunks_before_removing_document() {
        KnowledgeDoc doc = pendingDoc(9L);
        when(mapper.selectById(9L)).thenReturn(doc);
        when(aiService.removeIndex(9L)).thenReturn(true);

        service.delete(9L);

        verify(aiService).removeIndex(9L);
        verify(mapper).deleteById(9L);
    }

    @Test
    void delete_keeps_document_when_vector_cleanup_fails() {
        KnowledgeDoc doc = pendingDoc(9L);
        when(mapper.selectById(9L)).thenReturn(doc);
        when(aiService.removeIndex(9L)).thenReturn(false);

        Assertions.assertThrows(BizException.class, () -> service.delete(9L));

        verify(mapper, never()).deleteById(9L);
    }

    @Test
    void deleting_missing_document_throws_not_found() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.delete(9L));
    }
}
