/**
 * 工具向量索引文件。
 *
 * <h2>索引内容</h2>
 * <p>只索引静态属性（名称、类别、型号、押金、位置、损坏程度、使用说明），
 * 不索引是否可租等易变状态；查询时由数据库过滤可见性与可租性。
 *
 * <h2>一致性策略</h2>
 * <p>upsert 先删除旧 chunk 再写入新 chunk，避免文档数量减少时残留。
 * 失败进入并发安全的重试集合；定时任务以数据库当前状态为准决定 upsert 还是 remove。
 * 启动时全量重建，幂等且不需要在工具表维护索引状态字段。
 *
  * <h2>失败与重试语义</h2>
  * <p>upsert/remove 内部捕获所有异常：记录 WARN、把 toolId 放入 retryQueue。
  * 重试任务不保存“期望动作”，而是重新读取数据库状态后决定 upsert 或 remove，
  * 因此即使期间发生多次编辑、报废与恢复也能收敛到正确索引。
  * <p>重试队列是内存集合，进程重启后丢失；启动全量重建会兜底修正。
  *
  * <h2>文档 ID 与切块</h2>
  * <p>CHUNKER 使用固定前缀 tool、块大小 700、重叠 80、最小块 20；
  * 文档 ID 由工具 id 与块序号推导，稳定可预测，删除时无需先查询。
  * 文本变化导致块数减少时，先按旧 ID 全删再写入新块，避免残留。
  *
  * <h2>隐私</h2>
  * <p>索引只包含工具静态属性，不包含租用人、联系方式或租赁记录；
  * 使用说明先转为纯文本，避免 HTML 结构被嵌入向量库。
  *
  * <h2>启动成本</h2>
  * <p>全量重建逐条 upsert，工具量大时启动耗时较长；
  * 可通过 toolshare.ai.recommend.index-on-startup=false 关闭，依赖写时同步与重试。
  *
         * <h2>相关文件</h2>
         * <p>TextChunker（切块与 ID）、ToolMapper（重建数据源）、
         * ToolService（触发 upsert/remove）、VectorStore（Spring AI）。
        * <h2>故障恢复</h2>
        * <p>向量库恢复后无需手工操作：重试任务会按队列补齐，
        * 或在下一次编辑/审核/状态变更时重新 upsert。
       * <h2>监控指标</h2>
       * <p>建议观测：重试队列大小、索引失败次数、启动重建耗时、文档总数。
       * 重试队列持续非空通常表示向量库连接或 embedding 配额问题。
      * <h2>容量与成本</h2>
      * <p>索引文档数量约为工具数 × 平均块数；每个块都会产生一次 embedding 调用。
      * 工具文本越长，块数越多，重建与写时同步的成本越高。
      * <p>可通过调整块大小/重叠减少块数，但会牺牲长文本的召回粒度。
      * 重试队列只保存 id，不保存文本，因此不会额外占用内存。
     * <h2>重建步骤</h2>
     * <p>修改切块参数或 embedding 模型后建议：
     * 1）停止写时索引（tool-index-enabled=false）；
     * 2）按 tool- 前缀清空旧文档；
     * 3）启动应用触发全量重建；
     * 4）恢复写时索引并抽查推荐结果。
     * 重建期间用户端查询仍以数据库过滤为准，不会返回不可租工具。
    * <h2>配置项</h2>
    * <p>toolshare.ai.recommend.index-on-startup 控制启动全量重建；
    * toolshare.ai.tool-index-enabled 在 ToolService 侧控制写时同步。
    * 两者相互独立：可以关闭启动重建但保留写时同步，反之亦然。
    * 切块参数与向量库连接由 TextChunker/Spring AI 配置决定。
   * <h2>测试与本地开发</h2>
   * <p>未配置向量库时所有方法静默返回，工具主流程不受影响；
   * 可通过 index-on-startup=false 跳过启动全量重建。
   * 测试向量写入时可注入内存 VectorStore 或 mock ObjectProvider。
   * <p>切块参数（700/80/20）会直接影响召回质量与索引体积，
   * 调整前应同时评估 embedding 成本与检索效果。
   *
   * <h2>数据生命周期</h2>
   * <p>工具删除、报废、审核退回都会触发 remove；
   * 恢复可用、审核通过、编辑文本都会触发 upsert。
   * 索引不保存历史版本，旧 chunk 在写入前统一删除。
  * <h2>可观测性</h2>
  * <p>索引失败、删除失败、重建完成都有日志；
  * 失败日志带 toolId 与异常消息，便于按工具排查。
 * <h2>隔离</h2>
 * <p>文档 ID 使用 tool-{id}-{chunk} 前缀，与知识库 kb- 前缀隔离。
 *
 * <h2>容错</h2>
 * <p>向量库通过 ObjectProvider 可选注入；不可用时所有方法静默跳过，
 * 只记录日志，不影响工具主流程。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.ai.TextChunker;
import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.Dict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具向量索引：把可用于推荐的工具文本写入 Qdrant。
 * <p>索引 ID 使用 {@code tool-{id}-{chunk}} 派生，与知识库的 {@code kb-} 前缀隔离；
 * 索引内容只包含静态属性，是否可租在查询时由数据库过滤；报废/恢复/审核等状态变更仍同步删除或重建索引。
 * 外部向量库失败只记日志并进入重试队列，不影响工具主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolIndexService {

    private static final TextChunker CHUNKER = new TextChunker("tool", 700, 80, 20);

    // 重试与启动重建时以数据库当前状态为准
    private final ToolMapper toolMapper;
    // 向量库可能未配置，使用 ObjectProvider 做可选依赖
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    // 解析前端保存的 JSON 步骤数组
    private final ObjectMapper objectMapper;

    /** 索引失败的工具 id，定时任务按数据库当前状态重试 */
    // 并发安全的重试集合，避免同一工具重复排队
    private final Set<Long> retryQueue = ConcurrentHashMap.newKeySet();

    @Value("${toolshare.ai.recommend.index-on-startup:true}")
    // 测试或低成本环境可关闭启动全量重建
    private boolean indexOnStartup = true;

    // 先删后写：工具文本变化后 chunk 数量可能减少，必须先清掉旧 ID。
    // 任何一步失败都只记录并进入重试队列，不能影响工具主流程。
    /** 工具新增/编辑后调用：先清掉旧 chunk，再写入新内容 */
    public void upsert(Tool tool) {
        // 向量库可选；未配置时直接跳过
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 缺少任一必要上下文都无法构建索引
        if (vectorStore == null || tool == null || tool.getId() == null) {
            return;
        }
        // 任何外部调用失败都只降级，不向上抛
        try {
            // 先删旧 chunk：文本变短时，不删会留下过期内容
            vectorStore.delete(chunkIds(tool.getId()));
            // 重新切块并生成稳定文档 ID
            // 重新切块并生成稳定 ID
            List<Document> docs = buildDocs(tool);
            // 空文本不写入向量库
            if (!docs.isEmpty()) {
                vectorStore.add(docs);
            }
            // 成功即出队，避免后续重试覆盖新内容
            // 成功即出队
            retryQueue.remove(tool.getId());
        // 统一捕获取队列，避免异常类型泄漏到业务层
        } catch (Exception e) {
            // 任一步失败都入队，由定时任务按数据库状态收敛
            retryQueue.add(tool.getId());
            // 只记录日志，不影响工具主流程
            log.warn("工具向量索引失败，已加入重试队列 toolId={}: {}", tool.getId(), e.getMessage());
        }
    }

    /**
     * 删除工具的全部向量索引。
     * <p>用于工具报废或审核未通过；向量库不可用或调用失败时进入重试队列。
     *
     * @param toolId 工具 id
     */
    public void remove(Long toolId) {
        // 删除同样要求向量库可用
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 缺少 id 或向量库时无需处理
        if (vectorStore == null || toolId == null) {
            return;
        }
        try {
            // 删除该工具所有可能的 chunk，数量由当前文本长度决定
            vectorStore.delete(chunkIds(toolId));
            // 删除成功即出队
            retryQueue.remove(toolId);
        // 删除失败同样进入重试队列
        } catch (Exception e) {
            retryQueue.add(toolId);
            // 删除失败同样入队
            log.warn("删除工具向量索引失败，已加入重试队列 toolId={}: {}", toolId, e.getMessage());
        }
    }

    // 每 60 秒执行一次；只处理队列中的 id，数据库已删除的工具走 remove 清理残留。
    /** 重试失败条目：以数据库当前状态为准，报废/未审核的删除，其余重建。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    /**
     * 定时重试失败的索引操作。
     * <p>不记录“期望动作”，而是每次重新读取数据库状态判断：
     * 可索引则 upsert，不可索引则 remove，因此用户重复编辑或状态反复变化都能收敛。
     */
    public void retryFailed() {
        // 队列为空或向量库不可用时直接返回
        // 无待处理项或向量库不可用时直接返回
        if (retryQueue.isEmpty() || vectorStoreProvider.getIfAvailable() == null) {
            return;
        }
        // 复制快照遍历，避免遍历期间 upsert/remove 修改集合导致并发异常
        // 快照遍历，避免处理过程中集合被修改
        for (Long toolId : List.copyOf(retryQueue)) {
            // 以数据库当前状态为准，而不是失败时的快照
            // 以数据库当前状态为准
            Tool tool = toolMapper.selectById(toolId);
            // 可索引条件：存在、未报废、已审核通过
            // 可索引 = 存在 + 未报废 + 审核通过
            boolean indexable = tool != null
                    && !ToolStatus.SCRAPPED.code().equals(tool.getStatus())
                    && tool.getAuditStatus() != null
                    && tool.getAuditStatus() == AuditStatus.APPROVED.value();
            // 可索引则重建；否则删除可能残留的旧索引
            // 可索引则重建，否则清理残留
            // 可索引则重建，否则清理
            if (indexable) {
                upsert(tool);
            } else {
                remove(toolId);
            }
        }
    }

    /**
     * 启动后全量重建（幂等）。Qdrant / embedding 不可用时逐条记录并跳过，
     * 下次启动会自动重试，不需要在工具表里维护索引状态字段。
     */
    @EventListener(ApplicationReadyEvent.class)
    /**
     * 启动后按数据库全量重建可推荐工具的索引。
     * <p>过滤条件与查询时保持一致：审核通过且未报废；upsert 本身幂等，可重复执行。
     */
    public void rebuildAll() {
        // 配置允许关闭启动重建
        // 配置关闭时跳过启动重建
        if (!indexOnStartup) {
            return;
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 向量库不可用时跳过，下次启动再试
        // 向量库不可用时下次启动再试
        if (vectorStore == null) {
            return;
        }
        // 与查询口径一致：审核通过且未报废
        // 与在线查询口径一致：未报废且审核通过
        // 与在线查询口径一致
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .ne(Tool::getStatus, ToolStatus.SCRAPPED.code())
                .eq(Tool::getAuditStatus, AuditStatus.APPROVED.value()));
        // upsert 幂等，可重复执行
        // 逐条 upsert，幂等
        for (Tool tool : tools) {
            upsert(tool);
        }
        // 记录重建规模，便于观察启动行为
        // 记录重建规模
        log.info("工具向量索引重建完成，共 {} 个工具", tools.size());
    }

    /**
     * 构造工具的索引文档。
     * <p>只拼接静态属性，不包含是否可租等易变状态；
     * 文本先切块，再为每个 chunk 生成带 tool 前缀的稳定文档 ID 与元数据。
     *
     * @param tool 工具实体
     * @return 待写入向量库的文档列表，可能为空
     */
    private List<Document> buildDocs(Tool tool) {
        // 索引文本只拼接静态属性，不包含状态与库存等易变信息
        String text = """
                【工具】%s
                类别：%s
                型号：%s
                押金：%s 元
                存放位置：%s
                损坏程度：%s
                使用说明：%s
                """.formatted(
                nullToEmpty(tool.getName()),
                Dict.label(Dict.TOOL_CATEGORY, tool.getCategory()),
                nullToEmpty(tool.getModel()),
                tool.getDeposit(),
                nullToEmpty(tool.getStorageArea()),
                Dict.label(Dict.DAMAGE_LEVEL, tool.getDamageLevel()),
                plainText(tool.getUsageGuide()));
        // 长文本切块，保证 embedding 输入长度可控
        List<String> chunks = CHUNKER.split(text);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            // 文档 ID 稳定可推导，元数据保留 toolId 便于过滤与溯源
            docs.add(new Document(CHUNKER.documentId(tool.getId(), i), chunks.get(i), Map.of(
                    "kind", "tool",
                    "toolId", tool.getId(),
                    "name", nullToEmpty(tool.getName()),
                    "category", nullToEmpty(tool.getCategory())
            )));
        }
        return docs;
    }

    // 纯展示文本用于向量检索：JSON 步骤数组提取 text 字段，
    // HTML 则去掉标签并压缩空白；解析失败时按普通文本处理，绝不抛错。
    /** 使用说明可能是 HTML，也可能是前端保存的 JSON 步骤数组 */
    private String plainText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        // 先去掉首尾空白
        // 去掉首尾空白
        String value = raw.trim();
        // 前端步骤编辑器保存的是 JSON 数组，只提取 text 字段
        if (value.startsWith("[")) {
            try {
                // 尝试按 JSON 解析
                // 尝试解析 JSON
                JsonNode root = objectMapper.readTree(value);
                // 只有数组形态才是步骤编辑器保存的内容
                // 只有数组才是步骤编辑器格式
                if (root != null && root.isArray()) {
                    // 拼接所有步骤文本
                    // 拼接纯文本
                    StringBuilder builder = new StringBuilder();
                    // 逐步骤提取文本
                    // 提取每个步骤的 text
                    for (JsonNode node : root) {
                        // 缺失 text 的步骤跳过，避免把结构字段写入索引
                        String step = node.path("text").asText("");
                        // 跳过空步骤
                        // 跳过空步骤
                        if (StringUtils.hasText(step)) {
                            builder.append(step.trim()).append(' ');
                        }
                    }
                    // 有文本时直接返回
                    // 有内容则直接返回
                    if (!builder.isEmpty()) {
                        return builder.toString().trim();
                    }
                }
            // 解析失败按普通文本处理
            } catch (Exception ignored) {
                // 解析失败按普通文本处理
            }
        }
        // HTML 去标签并压缩空白，得到纯文本
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * 返回某工具所有可能存在的 chunk 文档 ID。
     *
     * @param toolId 工具 id
     * @return 文档 ID 列表
     */
    private List<String> chunkIds(Long toolId) {
        // 块 ID 由切块器统一推导
        // 块 ID 由切块器统一推导
        return CHUNKER.chunkIds(toolId);
    }

    /**
     * null 安全的字符串取值，避免索引文本出现 "null"。
     *
     * @param value 原值
     * @return 原值或空串
     */
    private String nullToEmpty(String value) {
        // null 归一为空串
        // null 归一为空串
        return value == null ? "" : value;
    }
}
