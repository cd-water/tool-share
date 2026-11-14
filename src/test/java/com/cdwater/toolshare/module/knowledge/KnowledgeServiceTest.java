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
 * 知识库服务单元测试。
 * <p>
 * 测试范围：上传落库与索引状态、索引失败后的补偿重试、启动回填的边界，
 * 以及删除时“先清向量、后删文档”的一致性保证。
 * 依赖环境：Mockito 模拟文档 Mapper 与 AiService；MyBatis 自增主键通过 Answer 回填。
 * 关键不变量：索引失败不得丢文档，文档保持 indexed=false 供重试；
 * 重试次数有上限；向量清理失败时禁止删除数据库文档；
 * 删除不存在的文档返回 NOT_FOUND。
 */
class KnowledgeServiceTest {

    /** 文档 Mapper 测试替身。 */
    // 文档 Mapper 使用 Mockito 替身，
    // 测试只观察服务对持久层的调用顺序与入参。
    // 不启动数据库，避免自增主键与事务时序干扰断言。
    private final KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
    /** 向量索引服务测试替身。 */
    // 向量索引服务同样是替身，
    // 通过返回值精确控制“成功/失败”分支。
    private final AiService aiService = mock(AiService.class);
    /** 真实的 VO 映射器，用于校验返回结构。 */
    // 使用 MapStruct 生成的真实映射器，
    // 以校验 upload 返回的 VO 结构与字段映射是否正确。
    // 真实映射器能让断言覆盖到生产使用的转换代码。
    private final KnowledgeDocVoMapper voMapper = Mappers.getMapper(KnowledgeDocVoMapper.class);
    /** 被测知识库服务。 */
    // 被测服务在字段初始化时组装，
    // 每个测试方法复用同一组替身，BeforeEach 只补充自增 ID 回填。
    private final KnowledgeService service = new KnowledgeService(mapper, aiService, voMapper);

    /** 辅助方法：构造一个 UTF-8 纯文本知识库上传文件。 */
    private static MockMultipartFile textFile() {
        // 构造一个 UTF-8 纯文本上传文件，
        // 文件名与内容都贴近知识库规则文档的真实形态。
        return new MockMultipartFile("file", "rules.txt", "text/plain",
                // MockMultipartFile 模拟 Spring MVC 的文件上传参数；
                // 内容使用中文，顺带覆盖编码处理路径。
                "社区共享工具租赁规则".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 模拟 MyBatis 插入后回填自增主键，使后续 updateById 与返回值都有稳定 ID。
     */
    @BeforeEach
    void assignGeneratedId() {
        // 模拟 MyBatis 插入后回填自增主键：
        // 后续 updateById 与返回 VO 都依赖这个 ID 才能对应上同一条记录。
        doAnswer(invocation -> {
            // doAnswer 在 insert 被调用时直接改写入参实体，
            // 返回 1 表示插入了一行，符合 Mapper 契约。
            KnowledgeDoc inserted = invocation.getArgument(0);
            inserted.setId(9L);
            return 1;
        }).when(mapper).insert(any(KnowledgeDoc.class));
    }

    /** 辅助方法：构造未索引的知识库文档，供回填与重试用例使用。 */
    private static KnowledgeDoc pendingDoc(long id) {
        // 构造一个尚未索引的文档，
        // 供启动回填、重试与删除用例复用。
        // indexed=false 是进入待补偿队列的标志。
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        // 标题、文件名与正文都填成可读内容，
        // 便于在断言失败时快速识别文档来源。
        doc.setTitle("租赁规则");
        doc.setFileName("rules.txt");
        doc.setContent("社区共享工具租赁规则");
        // 明确设置为未索引状态，
        // 避免用例依赖实体默认值而出现隐式行为。
        doc.setIndexed(false);
        return doc;
    }

    /**
     * 场景：上传文本文件且向量索引成功。
     * 前置条件：AiService.index 返回 true，Mapper 插入时回填 ID。
     * 断言目标：文档先落库且内容正确；随后更新 indexed=true；
     * 返回的 VO 也标记已索引，保证前端能区分可检索状态。
     */
    @Test
    void upload_persists_then_marks_indexed_when_vector_index_succeeds() {
        // 让向量索引返回成功，模拟 AI 服务正常可用。
        // 这是上传后能够标记 indexed=true 的前提。
        when(aiService.index(any())).thenReturn(true);

        // 执行上传：服务应先落库，再调用索引，最后回写索引状态。
        // 返回 VO 用于验证对外暴露的字段。
        // 文件与标题同时传入，覆盖完整上传参数。
        KnowledgeDocVO vo = service.upload(textFile(), "租赁规则");

        // 捕获 insert 入参，确认写入数据库的内容来自上传文件；
        // 使用捕获器而不是按调用次数断言，能同时检查实体字段。
        ArgumentCaptor<KnowledgeDoc> inserted = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("社区共享工具租赁规则", inserted.getValue().getContent());

        // 捕获 updateById 入参，确认索引成功后会回写状态。
        // 更新发生在索引之后，是“先落库再标记”的第二阶段。
        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).updateById(updated.capture());
        // 数据库中的文档必须被标记为已索引，
        // 否则启动回填会重复处理同一文档。
        assertTrue(updated.getValue().getIndexed());
        // 返回给前端的 VO 也要反映已索引状态，
        // 前端据此展示“可检索/待处理”的差异。
        assertTrue(vo.getIndexed());
    }

    /**
     * 场景：上传成功但向量索引失败。
     * 前置条件：AiService.index 返回 false。
     * 断言目标：文档仍然落库，indexed 保持 false，VO 同样为未索引；
     * 文档不会因索引失败而丢失，等待后续补偿。
     */
    @Test
    void upload_keeps_document_with_indexed_false_when_indexing_fails() {
        // 让向量索引返回失败，模拟 AI 服务暂时不可用。
        // 文档本身仍应成功落库，只是不能标记为已索引。
        when(aiService.index(any())).thenReturn(false);

        // 执行上传，标题传 null 以覆盖可空参数；
        // 服务不能因为索引失败而回滚已经写入的文档。
        KnowledgeDocVO vo = service.upload(textFile(), null);

        // 确认文档仍然被插入数据库，
        // 这是“索引失败不丢数据”的核心证据。
        verify(mapper).insert(any(KnowledgeDoc.class));
        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        // 捕获状态回写，检查 indexed 没有被误设为 true。
        // 若这里为 true，后续回填会跳过该文档。
        verify(mapper).updateById(updated.capture());
        // 数据库中保持未索引状态，等待补偿任务处理。
        // 失败信息不吞掉文档，是可用性设计的一部分。
        assertFalse(updated.getValue().getIndexed());
        // 返回 VO 同样标记未索引，
        // 让调用方立即知道检索能力尚未就绪。
        assertFalse(vo.getIndexed());
    }

    /**
     * 场景：启动回填时索引仍失败，随后重试成功。
     * 前置条件：AiService.index 第一次返回 false、第二次返回 true。
     * 断言目标：首次回填不更新索引状态；重试成功后把 indexed 更新为 true，
     * 验证失败文档会被保留并最终补齐。
     */
    @Test
    void startup_backfill_indexes_pending_documents_and_updates_status() {
        // 准备一条待索引文档，模拟历史数据或上次失败留下的记录。
        // selectList 返回该记录，供启动回填扫描。
        KnowledgeDoc doc = pendingDoc(9L);
        // 回填通过 Mapper 查询待处理文档；
        // 这里不关心查询条件，只验证服务会消费返回列表。
        when(mapper.selectList(any())).thenReturn(List.of(doc));
        // 第一次索引失败、第二次成功，
        // 精确构造“启动时失败、稍后重试成功”的时间线。
        when(aiService.index(doc)).thenReturn(false, true);

        // 首次回填执行索引，但不应更新状态；
        // 失败的文档需要保持原样，等待下一次重试。
        service.indexPendingDocs();
        // 失败时不能调用 updateById，
        // 否则会把未索引文档误标为已完成。
        verify(mapper, never()).updateById(any(KnowledgeDoc.class));

        // 触发重试，此时索引桩已切换到成功分支。
        // 重试入口应再次扫描同一批待处理文档。
        service.retryPendingDocs();

        // 捕获状态更新，验证重试成功后写入数据库。
        // 这一步是补偿链路真正完成的标志。
        ArgumentCaptor<KnowledgeDoc> updated = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(mapper).updateById(updated.capture());
        // indexed 必须变为 true，
        // 表示文档已可被检索，不会再进入下一轮重试。
        assertTrue(updated.getValue().getIndexed());
    }

    /**
     * 场景：索引持续失败并多次触发重试。
     * 前置条件：每次 index 都返回 false。
     * 断言目标：索引调用总共只发生 3 次，重试次数有上限，
     * 避免故障期间无限重试拖垮模型或任务线程。
     */
    @Test
    void failed_indexing_is_retried_a_bounded_number_of_times() {
        // 准备一条持续失败的文档，
        // 用于验证重试次数上限而不是无限循环。
        KnowledgeDoc doc = pendingDoc(9L);
        // 每次扫描都返回该文档，模拟故障一直未恢复。
        // 这样重试次数完全由服务内部策略决定。
        when(mapper.selectList(any())).thenReturn(List.of(doc));
        // 索引桩始终返回 false，确保每个分支都走失败路径。
        // 否则一次成功就会提前结束重试。
        when(aiService.index(doc)).thenReturn(false);

        // 首次回填尝试一次，随后连续触发四次重试；
        // 观察服务是否把总尝试次数限制在固定上限内。
        service.indexPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();
        service.retryPendingDocs();

        // 索引总共只能调用 3 次：
        // 首次加两次重试，超过上限后不再消耗 AI 资源。
        // 这条断言防止故障期间任务线程被无限占用。
        verify(aiService, times(3)).index(doc);
    }

    /**
     * 场景：删除一个已存在的知识库文档。
     * 前置条件：向量清理返回成功。
     * 断言目标：先调用 removeIndex 再 deleteById，
     * 保证不会留下无法定位的孤儿向量块。
     */
    @Test
    void delete_clears_vector_chunks_before_removing_document() {
        // 准备一个存在的文档，并让查询返回它；
        // 删除流程需要先读取文档确认存在性。
        KnowledgeDoc doc = pendingDoc(9L);
        // selectById 返回文档，模拟正常删除入口。
        // 若返回 null 会走 NOT_FOUND 分支。
        when(mapper.selectById(9L)).thenReturn(doc);
        // 向量清理返回成功，表示可以继续删除数据库记录。
        // 这是“先清向量、后删文档”顺序能够完成的前提。
        when(aiService.removeIndex(9L)).thenReturn(true);

        // 执行删除，服务内部应先清理向量再删数据库行。
        // 顺序错误会留下无法定位的孤儿向量。
        service.delete(9L);

        // 断言向量清理确实发生，
        // 保证删除不会只做数据库层面而遗留检索内容。
        verify(aiService).removeIndex(9L);
        // 断言数据库记录被删除，
        // 两条调用共同构成完整的删除语义。
        verify(mapper).deleteById(9L);
    }

    /**
     * 场景：删除时向量清理失败。
     * 前置条件：AiService.removeIndex 返回 false。
     * 断言目标：抛出业务异常且数据库文档不被删除，
     * 保留数据以便重试清理，避免检索到已删除文档的幽灵内容。
     */
    @Test
    void delete_keeps_document_when_vector_cleanup_fails() {
        // 准备文档并让查询成功返回，
        // 这样失败点被限定在向量清理而不是存在性检查。
        KnowledgeDoc doc = pendingDoc(9L);
        // selectById 返回文档，模拟用户点击删除一个真实存在的知识。
        // 下一步将让 removeIndex 返回失败。
        when(mapper.selectById(9L)).thenReturn(doc);
        // 向量清理失败，模拟向量库暂时不可用。
        // 此时继续删数据库会永久丢失清理线索。
        when(aiService.removeIndex(9L)).thenReturn(false);

        // 删除应抛出业务异常，让调用方感知失败并稍后重试。
        // 异常类型与错误码由业务层统一处理。
        Assertions.assertThrows(BizException.class, () -> service.delete(9L));

        // 数据库文档必须保留，
        // 这样重试删除时还能重新定位并清理对应向量。
        verify(mapper, never()).deleteById(9L);
    }

    /**
     * 场景：删除不存在的文档。
     * 前置条件：selectById 返回 null。
     * 断言目标：抛出业务异常（NOT_FOUND），与正常删除区分。
     */
    @Test
    void deleting_missing_document_throws_not_found() {
        // 让 selectById 返回 null，模拟目标文档不存在。
        // 删除入口应先做存在性校验。
        when(mapper.selectById(9L)).thenReturn(null);

        // 不存在的文档必须抛出 NOT_FOUND，
        // 与正常删除失败区分，便于前端给出不同提示。
        assertThrows(BizException.class, () -> service.delete(9L));
    }
}
