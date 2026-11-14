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
 * 工具向量索引服务单元测试。
 * <p>
 * 测试范围：工具索引的写入/替换、删除、向量库故障时的失败暂存与重试，
 * 以及文档元数据对推荐过滤的支撑。
 * 依赖环境：Mockito 模拟 VectorStore 与 ToolMapper，不依赖真实向量数据库。
 * 关键不变量：upsert 先删旧块再写新块；remove 只删不写；
 * 向量库异常不向主流程抛出，并在 retryFailed 时重试；
 * 文档必须携带 kind=tool 与 toolId 元数据；无向量库时为安全空操作。
 */
class ToolIndexServiceTest {

    /** 辅助方法：模拟 ObjectProvider，返回指定向量库或 null。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> provider(VectorStore store) {
        // 构造一个可控的 Spring ObjectProvider 替身，
        // 让被测服务通过 getIfAvailable() 取得向量库，
        // 模拟生产环境里“向量能力可能缺席”的注入方式。
        // 避免测试直接依赖自动配置的顺序。
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        // 指定 getIfAvailable 的返回值：传入 null 即模拟未启用向量库的场景。
        // 其余 ObjectProvider 方法在测试中不会用到。
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    /**
     * 辅助方法：构造一个字段完整的可索引工具，保证生成的文档文本非空。
     */
    private static Tool tool(long id) {
        // 构造一个字段齐全的工具实体，作为索引服务的输入样本。
        // 名称、分类、型号、押金、库位、成色、审核与状态都会被拼进向量文本，
        // 因此这里逐项赋值，确保后续生成的 Document 内容非空且贴近真实业务数据。
        // 固定 ID 为参数值，便于断言元数据中的 toolId。
        // 使用中文名称与型号，也能顺带覆盖非 ASCII 内容的入库路径。
        Tool tool = new Tool();
        tool.setId(id);
        // 基础描述字段：名称、分类编码与型号共同决定用户检索时的语义内容。
        // 分类使用 WX（维修工具）编码，与枚举字典保持一致。
        // 型号带上品牌，贴近真实工具库的数据形态。
        tool.setName("冲击电钻");
        tool.setCategory("WX");
        tool.setModel("博世 GSB 550");
        tool.setDeposit(new BigDecimal("200.00"));
        tool.setStorageArea("A 区");
        tool.setDamageLevel("NEW");
        tool.setAuditStatus(1);
        tool.setStatus("AVAILABLE");
        // 使用说明书以 HTML 片段保存，索引前会与其余字段一起转成纯文本。
        // 成色、审核状态与库存状态影响推荐过滤，但仍是文档正文的一部分。
        // 这里的字段组合刻意覆盖索引所需的全部属性。
        tool.setUsageGuide("<p>先断电再换钻头</p>");
        return tool;
    }

    /**
     * 场景：写入一个工具的索引。
     * 前置条件：向量库为 mock。
     * 断言目标：先按旧块 ID 删除，再写入新文档，
     * 确保更新工具信息时不会残留旧向量。
     */
    @Test
    void upsert_replaces_old_chunks_with_new_documents() {
        // 用 Mockito 替身承接向量库调用，
        // 测试只关心“调用了哪些方法、顺序是否正确”，不需要真实嵌入模型。
        // 这样断言不会受外部 Qdrant 服务可用性影响。
        // 向量库仅作为协作者被观察。
        VectorStore store = mock(VectorStore.class);
        // 组装被测服务：ToolMapper 同样使用哑元，ObjectMapper 使用真实实现。
        // 本用例不触发数据库访问，只验证 upsert 对向量库的编排逻辑。
        // 依赖全部显式传入，避免 Spring 上下文带来的不确定性。
        // 真实 ObjectMapper 能保证文档序列化路径与生产一致。
        // 哑元 Mapper 不会返回数据，正好隔离数据库。
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        // 执行一次工具索引写入，模拟工具新增或信息变更后的同步动作。
        // 服务内部会先把工具转换为若干 Document 块，
        // 再按确定性规则计算旧块 ID 并清理，最后写入新块。
        // 这一步是本用例观察的全部行为入口。
        // 调用本身不应抛异常。
        service.upsert(tool(42L));

        // 断言“先删旧块”：更新索引必须移除上一版本残留的向量，
        // 否则同一工具会出现重复或过期内容，影响推荐与检索质量。
        // anyList() 表示这里只验证发生了删除，不绑定具体块 ID 列表。
        // 删除与写入的先后顺序由下游实现保证，本用例确认删除被触发。
        // 这是 upsert 语义区别于纯 add 的关键。
        verify(store).delete(anyList());
        // 断言“再写新块”：旧向量清理后必须写入当前版本的内容，
        // 保证工具信息变更能立即反映到检索结果中。
        // 与上一条断言共同构成“替换而不是追加”的业务约定。
        // add 的入参在另一个用例中会进一步检查元数据。
        // 这里只确认写入动作确实发生。
        verify(store).add(anyList());
    }

    /**
     * 场景：删除一个工具的索引。
     * 前置条件：工具 ID 已知。
     * 断言目标：只调用 delete，不调用 add，删除语义清晰。
     */
    @Test
    void remove_deletes_chunk_ids_without_adding_documents() {
        // 删除索引同样只需观察向量库交互，
        // 使用 mock 隔离外部依赖，测试稳定且无需网络。
        // 工具 ID 固定为 42，便于核对 delete 的调用来源。
        // 删除动作本身不访问数据库。
        VectorStore store = mock(VectorStore.class);
        // 构造被测服务，Mapper 与 ObjectMapper 仅满足构造参数要求。
        // remove 路径不会读取工具实体，因此无需准备数据库返回值。
        // 依赖显式注入，保持测试与 Spring 容器解耦。
        // 所有断言都落在向量库替身上。
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        // 执行索引删除，模拟工具下架或删除后的清理动作。
        // 生产语义是：工具不再可检索，向量库中不能留下孤儿块。
        // 该调用应自行吞掉可恢复的异常，不阻塞业务主流程。
        // 本用例只验证正常路径的调用编排。
        service.remove(42L);

        // 删除动作必须真正下发到向量库，
        // 否则下架工具仍可能被语义检索命中。
        // 这里只断言“发生了一次删除”，不限定具体 ID 集合。
        // 具体的块 ID 规则由索引实现负责。
        verify(store).delete(anyList());
        // 删除路径绝不能写入新文档：remove 与 upsert 的语义必须严格区分。
        // 若这里出现 add，说明实现把删除误当成重建，会重新暴露已下架内容。
        // never() 明确表达“零次调用”的期望。
        // 这条断言与上一条共同锁定 remove 的最小行为契约。
        // 两条一起，任何把删除做成重建的回归都会失败。
        verify(store, never()).add(anyList());
    }

    /**
     * 场景：首次写入向量库抛异常，随后恢复。
     * 前置条件：add 第一次抛异常、之后成功；Mapper 可按 ID 查回工具。
     * 断言目标：upsert 不向主流程抛异常；retryFailed 后 add 被调用两次，
     * 证明失败任务被暂存并成功重试。
     */
    @Test
    void vector_store_failure_does_not_block_main_flow_and_is_retried() {
        // 使用 mock 精确控制第一次写入失败、第二次成功，
        // 模拟向量数据库短暂不可用的线上故障。
        // doThrow...doNothing 的链式写法为连续调用准备不同结果。
        VectorStore store = mock(VectorStore.class);
        // 第一次 add 抛出运行时异常，第二次正常返回。
        // 异常信息包含下游组件名称，便于模拟真实错误上下文。
        // 这是验证“失败暂存 + 重试”链路的关键前置条件。
        // Mockito 会按调用次数依次消费这两个桩值。
        doThrow(new IllegalStateException("qdrant down")).doNothing().when(store).add(anyList());
        // Mapper 需要能在重试时按 ID 重新查回工具，
        // 因为失败任务通常只保存工具 ID 而不是整个实体。
        // 这里准备一个返回完整工具的替身。
        ToolMapper toolMapper = mock(ToolMapper.class);
        // 固定 ID 42 与 upsert 入参一致，
        // 保证重试时查到的是同一个工具，断言才有意义。
        // 若返回 null，重试逻辑会走另一条分支。
        when(toolMapper.selectById(42L)).thenReturn(tool(42L));
        // 组装被测服务，注入可控的失败向量库与可回查的 Mapper。
        // 两个依赖的配合模拟了“主流程先失败、后台再补偿”的真实运行方式。
        // 真实 ObjectMapper 负责把工具转成文档。
        // 测试不启动 Spring，故障注入完全在进程内完成。
        ToolIndexService service = new ToolIndexService(toolMapper,
                provider(store), new ObjectMapper());

        // 主流程调用不得因为向量库故障而抛异常：
        // 工具新增/修改是核心业务，索引只是附加能力，不能反向阻塞写库。
        // assertDoesNotThrow 直接表达这一可用性不变量。
        // 这也是索引服务采用“尽力而为”语义的原因。
        assertDoesNotThrow(() -> service.upsert(tool(42L)));

        // 触发失败任务重试，模拟后台补偿任务或启动回填。
        // 此时向量库已恢复，暂存的工具应被重新索引。
        // 重试入口必须幂等且可重复调用，不依赖首次失败的上下文。
        // 本用例只重试一次，观察调用次数。
        service.retryFailed();

        // add 总共应被调用两次：首次失败一次，重试成功一次。
        // 若只调用一次，说明失败任务没有被暂存；
        // 若超过两次，说明重试产生了重复写入。
        // times(2) 同时排除这两种回归。
        verify(store, times(2)).add(anyList());
    }

    /**
     * 场景：未配置向量库时执行 upsert 与 remove。
     * 前置条件：ObjectProvider 返回 null。
     * 断言目标：两个操作都不抛异常，无向量能力时索引是安全空操作。
     */
    @Test
    void missing_vector_store_is_a_no_op() {
        // ObjectProvider 返回 null，模拟未配置向量库的部署环境。
        // 这是可选能力的典型场景：本地开发或未启用 AI 检索时。
        // 服务仍需能正常构造，不能把向量库当成强依赖。
        // 后续两个调用都应安全降级。
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(null), new ObjectMapper());

        // 无向量库时 upsert 应是无害空操作：
        // 业务写库已完成，索引能力缺席不应引发异常。
        // 返回值或副作用都不应影响调用方。
        assertDoesNotThrow(() -> service.upsert(tool(42L)));
        // remove 同样要能安全降级；
        // 下架工具时即使没有索引，也不能因为清理失败而阻塞。
        // 两个操作都不抛异常，才算真正把向量库做成可选依赖。
        // 该断言补全了“缺失依赖”场景下的完整契约。
        assertDoesNotThrow(() -> service.remove(42L));
    }

    /**
     * 场景：写入工具索引并捕获向量文档。
     * 前置条件：推荐召回依赖文档元数据做过滤。
     * 断言目标：元数据包含 kind=tool 与 toolId=42，
     * 保证后续可按业务类型与工具 ID 精确筛选。
     */
    @Test
    void documents_carry_tool_metadata_for_recommendation_filtering() {
        // 使用 mock 捕获写入向量库的文档，
        // 以验证推荐过滤所依赖的元数据是否随文档一起落库。
        // 测试不关心向量数值，只关心业务标签。
        // 捕获通过 ArgumentCaptor 在写入后完成。
        VectorStore store = mock(VectorStore.class);
        // 组装服务时传入空 Mapper，索引内容完全来自入参工具实体。
        // 这样可以确认元数据由转换逻辑产生，而不是数据库查询补全。
        // 真实 ObjectMapper 保证 Document 的结构与生产一致。
        // 依赖关系保持最小。
        ToolIndexService service = new ToolIndexService(mock(ToolMapper.class),
                provider(store), new ObjectMapper());

        // 执行索引写入，触发工具到文档的转换与向量库写入。
        // 该调用之后即可从捕获到的 Document 中检查元数据。
        // 这里的工具 ID 为 42，后续断言应与之吻合。
        // 写入动作本身不抛异常。
        service.upsert(tool(42L));

        // 准备参数捕获器，用于截获传递给 VectorStore.add 的文档列表。
        // 由于 add 只接收列表，需要先捕获列表再取第一个元素。
        // 泛型擦除使得捕获时需要显式转换为 List<Document>。
        // 抑制警告只影响测试可读性，不改变行为。
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Document>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        // 验证 add 被调用并取得其入参；
        // 捕获成功后即可对文档内容和元数据做精确断言。
        // 这一步也间接确认索引写入确实经过向量库。
        // 没有捕获到文档时，后续取值会直接失败。
        verify(store).add(captor.capture());
        // 取出第一个文档块的元数据，检查推荐过滤所需的业务标签。
        // kind 用于区分工具、知识库等不同来源，避免跨类型召回；
        // toolId 用于把向量结果映射回具体工具实体。
        // 多个块共享同一组元数据，取首块即可代表本次写入。
        Map<String, Object> metadata = captor.getValue().getFirst().getMetadata();
        // 断言来源类型为 tool，保证推荐时能按业务类型过滤；
        // 同时断言 toolId 与入参 42 一致，保证结果可回表查询。
        // 两条断言共同锁定向量的可追溯性。
        // 元数据缺失会导致推荐结果无法关联到业务对象。
        org.junit.jupiter.api.Assertions.assertEquals("tool", metadata.get("kind"));
        org.junit.jupiter.api.Assertions.assertEquals(42L, metadata.get("toolId"));
    }
}
