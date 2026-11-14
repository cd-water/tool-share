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
 * 知识库向量索引单元测试。
 * <p>
 * 测试范围：文档切块 ID 的确定性、重复索引幂等、内容缩短后的陈旧块清理、
 * 索引异常隔离，以及删除索引后的不可检索性。
 * 依赖环境：内存 VectorStore 替身，不依赖真实嵌入模型或向量数据库。
 * 关键不变量：同一文档同一片段生成稳定 ID，重复索引覆盖而非追加；
 * 重新索引必须清除多余旧块；向量服务故障时 index 返回 false 而不抛异常；
 * 删除后所有块不可被检索。
 */
class AiServiceIndexTest {

    /** 辅助方法：用指定向量库构造 AiService，知识库 Mapper 与 ChatClient 用哑元顶替。 */
    private static AiService service(VectorStore store) {
        // 用给定的向量库构造被测服务；
        // 知识库 Mapper 与 ChatClient 在本组用例中不参与断言，使用哑元隔离。
        // 服务内部只需向量库即可完成切块、索引与删除。
        // 这样测试不依赖数据库和真实大模型。
        return new AiService(mock(KnowledgeDocMapper.class), provider(store), provider(null));
    }

    /** 辅助方法：模拟 ObjectProvider，按需返回依赖或 null。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        // 构造可指定返回值的 ObjectProvider 替身，
        // 用于模拟“向量库已配置/未配置”两种运行环境。
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        // getIfAvailable 是服务读取可选依赖的唯一入口，
        // 统一在这里设置返回值即可覆盖空与非空分支。
        // 其他 Optional 方法不在被测路径上。
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** 辅助方法：构造指定 ID 与正文的知识库文档。 */
    private static KnowledgeDoc doc(long id, String content) {
        // 构造一段知识库文档样本：固定 ID 便于断言块 ID，
        // 标题与正文会一起参与切块。
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        // 正文内容由调用方传入，
        // 便于用不同长度的文本来控制切块数量与边界场景。
        // 标题固定，避免无关变量影响断言。
        doc.setTitle("租赁规则");
        doc.setContent(content);
        return doc;
    }

    /**
     * 场景：为 1600 字文档建立索引，并重复索引一次。
     * 前置条件：切块大小固定，文档被切成 3 块。
     * 断言目标：块 ID 与预期确定性 UUID 完全一致；重复索引后仍为 3 块，
     * 证明同一文档覆盖写入而非追加重复向量。
     */
    @Test
    void index_uses_deterministic_chunk_ids_and_is_idempotent() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        // 使用内存向量库替身记录写入结果，
        // 这样可以断言块数量与确定性 ID，而不依赖真实嵌入服务。
        // 不计算相似度，只保存文档。
        // 测试聚焦索引编排本身。
        AiService service = service(store);
        // 组装服务，Mapper 与 ChatClient 为哑元，
        // 本用例只观察向量库中的文档。
        // 依赖关系最小化，便于定位失败。
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));

        // 1600 字在固定切块大小下恰好切成 3 块，
        // 这个长度是后续 ID 与清理断言的稳定前置条件。
        // 若切块策略调整，需要同步更新期望值。
        assertTrue(service.index(doc));
        // 首次索引应成功，服务返回 true 表示所有块都已写入。
        // 返回 false 通常意味着向量服务不可用，下方断言会立即暴露。
        assertEquals(3, store.documents().size());
        // 块数量必须是 3：
        // 少于预期说明内容被截断，多于预期说明切块边界处理有误。
        // 数量是后续“重复索引不追加”的基线。
        assertEquals("57fdc175-9427-3e8b-93e5-129340c438b4", store.documents().get(0).getId());
        // 块 ID 由文档 ID、块序号与内容确定性生成，
        // 固定 UUID 断言可以防止哈希算法或 salt 被无意修改。
        // 稳定 ID 是重复索引能够覆盖旧块的前提。
        assertEquals("446b0f2e-d816-3ef1-9c2e-2fd4f55c590b", store.documents().get(1).getId());
        // 第二块的 ID 独立于第一块，
        // 说明块序号已被纳入 ID 计算，不同片段不会互相覆盖。
        // 逐块断言比只检查首块更能发现生成逻辑偏移。
        assertEquals("384b8f0b-94d8-3f67-921f-092290e9c507", store.documents().get(2).getId());
// 第三块同样有稳定的独立 ID，
// 三块合起来证明整套切块编号可复现。
// 这些常量即生产环境中可依赖的索引键。

        assertTrue(service.index(doc));
        // 对同一文档再次索引，模拟内容未变但触发重新同步的场景。
        // 期望行为是覆盖写入，而不是追加三份重复向量。
        assertEquals(3, store.documents().size(), "重复索引应替换而不是追加 chunk");
    // 块数仍为 3，证明写入使用了相同 ID 完成覆盖；
    // 若变成 6，说明旧块未被清理，检索结果会出现重复内容。
    // 错误信息点明“替换而非追加”的业务约定。
    // 这条断言是本用例的核心不变量。
    }

    /**
     * 场景：文档内容从 1600 字缩短为短文本后重新索引。
     * 前置条件：新内容只需 1 个块。
     * 断言目标：向量库只剩 1 块，旧的两个块被清理，
     * 避免已删除内容仍被检索命中。
     */
    @Test
    void reindex_after_content_shrinks_removes_stale_chunks() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        // 先按 1600 字建立 3 块索引，再缩短正文；
        // 场景对应知识库文档被编辑、删减内容的常见操作。
        AiService service = service(store);
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));
        // 首次索引建立完整的 3 块基线，
        // 后续才能观察缩短内容后多余块是否被清理。
        service.index(doc);

        // 将正文替换为只需 1 块的短文本，
        // 模拟文档内容大幅缩减后的重新同步。
        // 这是“内容缩短”场景的关键前置条件。
        doc.setContent("更新后的短内容");
        // 重新索引应成功；
        // 服务需要先识别旧块集合，再删除新内容用不到的尾块。
        // 这是“内容缩短”场景的关键分支。
        assertTrue(service.index(doc));

        // 向量库最终只能保留 1 块：
        // 前两个旧块必须被删除，否则已删掉的正文仍可能被检索到。
        // 块数量直接反映陈旧数据是否清理干净。
        assertEquals(1, store.documents().size());
        // 保留下来的块沿用第一块的确定性 ID；
        // 这说明新内容覆盖到了正确位置，而不是生成了一份全新索引。
        // ID 与数量的组合断言覆盖了清理与覆盖两个动作。
        assertEquals("57fdc175-9427-3e8b-93e5-129340c438b4", store.documents().get(0).getId());
    }

    /**
     * 场景：向量库 add 抛出异常。
     * 前置条件：内存替身开启 failAdd。
     * 断言目标：index 返回 false 而不向调用方抛异常，
     * 让知识库服务可以把文档标记为待重试。
     */
    @Test
    void index_failure_returns_false_instead_of_throwing() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        // 打开故障注入：下一次 add 会模拟嵌入服务不可用。
        // 该场景验证索引失败的隔离边界。
        store.failAdd = true;

        // 索引失败应返回 false，而不是把异常抛给上传/保存主流程；
        // 调用方据此可以把文档标记为待重试，避免因 AI 能力故障丢失业务数据。
        // 返回布尔值而非异常的契约在此被锁定。
        assertFalse(service(store).index(doc(7L, "内容")));
    }

    /**
     * 场景：删除文档索引后再次检索。
     * 前置条件：文档已索引 3 块。
     * 断言目标：向量库为空且 retrieve 返回空，
     * 证明删除覆盖全部块而非只删第一块。
     */
    @Test
    void remove_index_clears_every_chunk_so_it_cannot_be_retrieved() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        // 先建立 3 块索引，模拟文档已正常入库并完成向量化。
        // 后续删除需要覆盖全部块，而不只是首块。
        AiService service = service(store);
        KnowledgeDoc doc = doc(7L, "a".repeat(1600));
        // 再次索引以保证测试数据处于预期状态；
        // 这里不重复断言返回值，聚焦删除行为。
        // 3 块是删除完整性的观察基线。
        service.index(doc);

        // 删除文档索引，模拟知识库文档被下架或物理删除。
        // 返回值 true 表示清理流程正常结束。
        // 删除应幂等，即使重复调用也不应留下残块。
        assertTrue(service.removeIndex(7L));

        // 向量库中不能残留任何块：
        // 否则已删除知识仍可能出现在问答检索结果中。
        // 完整性要求覆盖全部块，而不只是第一个。
        assertTrue(store.documents().isEmpty());
        // 通过检索再次确认不可见性：
        // 内存替身会返回全部文档，因此空结果等价于向量库已清空。
        // 业务层面的“不可检索”比单纯计数更接近真实约束。
        assertTrue(service.retrieve("租赁").isEmpty());
    }

    /**
     * 场景：未配置向量库时执行删除与索引。
     * 前置条件：ObjectProvider 返回 null。
     * 断言目标：removeIndex 安全返回 true；index 返回 false，
     * 无向量能力时既不崩溃也不误报索引成功。
     */
    @Test
    void remove_index_without_vector_store_is_a_no_op_success() {
        AiService service = service(null);
// 构造没有向量库的服务实例，模拟未启用 AI 检索的部署。
// 这是可选能力的典型降级路径。

        // 无向量库时删除索引应视为成功：
        // 没有需要清理的向量，调用方无需感知能力缺失。
        // 返回 true 表示操作在语义上已完成。
        assertTrue(service.removeIndex(7L));
        // 索引则必须返回 false：
        // 没有向量库就不可能真正建立索引，不能误报成功。
        // 两个返回值共同体现“删除可空操作、索引不可伪装”的区别。
        // 调用方据此决定是否稍后重试。
        assertFalse(service.index(doc(7L, "内容")));
    }

    /**
     * 内存向量库替身：按 ID 保存文档，检索直接返回全部内容。
     * 只实现测试需要的 add/delete/similaritySearch，降低外部依赖。
     */
    private static final class InMemoryVectorStore implements VectorStore {

        /** 有序映射，便于断言块 ID 的生成顺序。 */
        // 使用有序 Map 保留写入顺序，
        // 这样断言块 ID 时可以直接按位置取第一、第二、第三块。
        // 顺序是确定性切块的外在表现之一。
        private final Map<String, Document> store = new LinkedHashMap<>();
        /** 为 true 时 add 抛异常，用于验证索引失败隔离。 */
        // 故障开关：为 true 时 add 抛异常，
        // 让上层用例可以验证索引失败的隔离与返回语义。
        // 开关默认关闭，正常用例不受影响。
        private boolean failAdd;

        /** 写入文档；故障模式下直接抛出异常。 */
        @Override
        public void add(List<Document> documents) {
            // 故障模式下模拟向量服务不可用；
            // 抛出的异常应由被测服务捕获并转换为 false。
            if (failAdd) {
                throw new IllegalStateException("embedding service down");
            }
            for (Document document : documents) {
                // 正常路径按 ID 写入或覆盖：
                // 相同 ID 的文档会替换旧值，这正是幂等索引依赖的行为。
                // 使用 LinkedHashMap 还能保持首次写入顺序。
                store.put(document.getId(), document);
            }
        }

        /** 按 ID 删除文档块。 */
        @Override
        public void delete(List<String> ids) {
            // 按块 ID 删除；
            // 删除不存在的 ID 是安全空操作，便于清理逻辑重复执行。
            // 生产实现可能批量删除，这里逐个移除即可满足断言。
            ids.forEach(store::remove);
        }

        /** 本测试不使用按过滤条件删除，显式不支持。 */
        @Override
        public void delete(Filter.Expression filterExpression) {
            // 本测试不覆盖按表达式删除的分支，
            // 显式抛出不支持异常，避免误以为该路径已被验证。
            // 如果未来服务新增该调用，测试会立即失败并提醒补齐实现。
            throw new UnsupportedOperationException("not used in this test");
        }

        /** 检索替身：不计算相似度，直接返回当前全部文档。 */
        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            // 检索替身不做相似度排序，直接返回全部文档；
            // 对本组用例而言，“返回空/非空”足以验证删除是否彻底。
            return new ArrayList<>(store.values());
        }

        /** 返回当前文档快照，供断言块数量与 ID。 */
        List<Document> documents() {
            // 返回当前文档快照副本，
            // 断言只读取快照，不会意外修改内部存储。
            // 副本也避免断言代码直接操作内部 Map。
            return new ArrayList<>(store.values());
        }
    }
}
