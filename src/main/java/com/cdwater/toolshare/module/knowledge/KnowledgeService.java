package com.cdwater.toolshare.module.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.ai.AiService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 知识库文档服务：负责文档上传、解析、向量切块索引以及索引失败重试。
 * <p>核心流程：
 * <ol>
 *   <li>上传：解析文件正文并落库，indexed 先置为 false；</li>
 *   <li>索引：把正文切成固定大小 chunk，写入向量库，成功后 indexed=true；</li>
 *   <li>重试：应用启动和每分钟定时扫描未索引文档，有限次数重试；</li>
 *   <li>删除：先清理向量 chunk，再删除文档，避免「文档已删但仍被检索」。</li>
 * </ol>
 * <p>切块与主键规则由 {@link com.cdwater.toolshare.shared.ai.TextChunker}
 * 统一决定：同一文档的 chunk 主键是确定性的，因此重建索引时可以先按
 * id 范围删除旧 chunk，再写入新内容，重复执行保持幂等。
 * <p>失败策略：外部向量库或 AI 服务不可用时，上传与列表仍然成功，
 * 只把 indexed 保持为 false；索引失败不影响文档正文的保存与展示。
 * 单进程内每个文档最多尝试 {@link #MAX_INDEX_ATTEMPTS} 次，耗尽后等待
 * 下次启动再试，避免外部服务长期不可用时无限刷日志。
 * <p>为什么先保存正文：索引是派生数据，可以随时从正文重建；让上传成功
 * 但标记未索引，比让用户重新上传整份文档更友好。
 * <p>为什么删除要先清向量：如果先删数据库，后续清理失败将无法再通过
 * 文档 ID 推导 chunk 主键，检索会长期命中已删除内容。
 * <p>并发说明：索引重试通过 ConcurrentHashMap 记录尝试次数，允许多次
 * 调度下仍能累计失败次数；索引本身由向量库与数据库各自保证幂等。
 */
public class KnowledgeService {

    /** 单进程内索引最大尝试次数；达到后不再自动重试，直到下次启动。 */
    private static final int MAX_INDEX_ATTEMPTS = 3;

    /** 知识库文档持久化访问入口。 */
    private final KnowledgeDocMapper knowledgeDocMapper;
    /** AI 服务，负责切块、写入向量库与清理向量索引。 */
    private final AiService aiService;
    /** 文档实体到视图对象的映射器，负责生成正文预览。 */
    private final KnowledgeDocVoMapper knowledgeDocVoMapper;

    /** 文档 ID 到当前进程内失败次数的映射；成功或删除后移除。 */
    private final Map<Long, Integer> indexAttempts = new ConcurrentHashMap<>();

    /**
     * 查询全部知识库文档，按创建时间倒序。
     * <p>只返回元数据与正文预览，不返回完整正文，避免列表响应过大。
     *
     * <p>列表只返回元数据与正文预览，不返回完整正文。
     * @return 文档视图列表
     */
    public List<KnowledgeDocVO> list() {
        // 列表只读，不触发索引或向量操作。
        // 正文预览由映射器生成。
        // 结果按创建时间倒序。
        // 按创建时间倒序返回全部文档。
        // 只返回元数据与正文预览。
        // 不返回完整正文，控制响应大小。
        return knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                        .orderByDesc(KnowledgeDoc::getCreateTime))
                .stream().map(knowledgeDocVoMapper::toVO).toList();
    }

    /**
     * 上传并索引一份知识库文档。
     * <p>解析失败或正文为空时直接返回 400；正文落库后立即尝试建立向量
     * 索引。索引失败不会回滚文档，而是保留 indexed=false，由启动补建或
     * <p>重试计数从 1 开始，因为上传阶段已经尝试过一次。
     * <p>索引失败时文档仍然可读，只是暂时无法被问答检索。
     * <p>返回的 indexed 表示本次同步索引是否成功，不代表后续不会重试。
     * 定时任务重试，保证用户上传的内容不丢失。
     *
     * @param file  上传文件，支持 txt/md/csv 及 Tika 可解析的格式
     * @param title 可选标题，为空时使用文件名
     * @return 新建文档的视图对象
     * <p>索引失败不会回滚文档，而是保留 indexed=false 等待重试。
     * @throws BizException 文件为空、正文为空或无法解析时抛出
     */
    public KnowledgeDocVO upload(MultipartFile file, String title) {
        // 标题为空时使用文件名。
        // 解析结果为空时不落库。
        // 索引失败不影响文档保存。
        // 空文件或空正文直接拒绝。
        // 正文落库后先标记未索引。
        // 同步尝试一次索引，失败保留文档等待重试。
        // 空文件没有可索引内容，直接拒绝，避免产生永远无法索引的脏记录。
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择知识库文档");
        }
        // 原始文件名可能为空（部分客户端不传），使用占位名保证展示完整。
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "文档.txt";
        // 解析在落库前完成：无法解析的文件不产生数据库记录。
        String content = parse(file);
        // 解析结果为空同样视为失败，否则会写入一条永远无法检索的文档。
        if (!StringUtils.hasText(content)) {
            throw new BizException(ResultCode.BAD_REQUEST, "文档内容为空或无法解析");
        }
        // 先落库拿到自增 ID，后续切块主键依赖该 ID。
        KnowledgeDoc doc = new KnowledgeDoc();
        // 管理员可自定义标题；未填写时回退到文件名。
        doc.setTitle(StringUtils.hasText(title) ? title.trim() : fileName);
        // 保留原始文件名用于展示与追溯，不参与检索。
        doc.setFileName(fileName);
        // 正文是切块与预览的唯一来源，统一去除首尾空白。
        doc.setContent(content.trim());
        // 先标记未索引，索引成功后再更新，避免列表误显示为已检索。
        doc.setIndexed(false);
        // 插入后回填自增 ID，供切块主键与后续更新使用。
        knowledgeDocMapper.insert(doc);
        // 同步尝试一次索引；失败时保留 false 并进入重试队列。
        doc.setIndexed(aiService.index(doc));
        // 只更新索引标记与时间等字段，正文保持不变。
        knowledgeDocMapper.updateById(doc);
        // 首次索引失败：登记一次尝试，等待启动补建或定时任务重试。
        if (!doc.getIndexed()) {
            // 上传阶段已尝试过一次，因此计数从 1 开始。
            indexAttempts.put(doc.getId(), 1);
            log.warn("知识库文档索引失败，已保留文档并等待重试: {}", doc.getTitle());
        }
        // 返回预览而非完整正文，接口响应保持轻量。
        return knowledgeDocVoMapper.toVO(doc);
    }

/**
 * 删除知识库文档。
 * <p>必须先清理向量 chunk 再删除数据库记录：如果向量清理失败，文档
 * 仍然保留，避免出现「数据库已删但检索仍命中」的不一致状态。
 * <p>向量清理成功但数据库删除失败时，文档会保留为未索引状态，再次删除
 * 会重新尝试清理，操作保持幂等。
 *
 * @param id 文档主键
 * <p>向量清理失败时文档保持原样，等待用户重试。
 * @throws BizException 文档不存在或向量索引清理失败时抛出
 */
    public void delete(Long id) {
        // 文档不存在时返回 404。
        // 向量清理失败时保留文档并返回 500。
        // 删除成功后清除重试计数。
        // 先确认文档存在。
        // 再清理向量 chunk，失败则保留文档。
        // 向量清理成功后才删除数据库记录。
        // 先确认文档存在，便于区分 404 与向量清理失败。
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        // 重复删除返回 404，前端可据此刷新列表。
        if (doc == null) {
            throw new BizException(ResultCode.NOT_FOUND, "知识库文档不存在");
        }
        // 向量清理失败时直接中断，数据库记录保持原样，等待用户重试。
        if (!aiService.removeIndex(id)) {
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "向量索引清理失败，请稍后重试");
        }
        // 向量清理成功后才删除正文，保证检索与数据库一致。
        knowledgeDocMapper.deleteById(id);
        // 文档已删除，清除重试计数，避免内存中残留无效 ID。
        indexAttempts.remove(id);
    }

    /** 应用启动后为未建立向量索引的文档补建索引（含 SQL 种子数据）。 */
    @EventListener(ApplicationReadyEvent.class)
    /**
     * 应用启动后补建索引。
     * <p>覆盖两类文档：历史 SQL 种子数据，以及上次运行期间索引失败、
     * <p>补建覆盖 SQL 种子数据与运行期上传失败的文档。
     * 进程退出时仍未成功的数据。补建在启动阶段同步执行，失败不阻塞启动。
     */
    public void indexPendingDocs() {
        // 应用启动后补建索引。
        // 覆盖 SQL 种子数据与历史失败文档。
        // 失败不阻塞启动。
        indexPending();
    }

    /** 运行期定时重试失败的索引，单进程内有限次，避免外部 AI 长期不可用时无限刷日志。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    /**
     * 定时重试未索引文档。
     * <p>每分钟执行一次，首次延迟 60 秒，避免与启动补建重复；单个文档
     * <p>定时任务与启动补建共用同一套扫描逻辑。
     * 达到最大尝试次数后跳过，直到下次进程启动。
     */
    public void retryPendingDocs() {
        // 每 60 秒扫描一次未索引文档。
        // 与启动补建共用同一套逻辑。
        // 达到最大尝试次数的文档跳过。
        indexPending();
    }

    /**
     * 扫描未索引文档并逐个尝试建立索引。
     * <p>查询条件同时覆盖 false 与 NULL，兼容历史数据中未写入该字段的
     * 文档。成功后将 indexed 更新为 true 并清除尝试计数；失败则累加计数，
     * <p>达到最大尝试次数的文档只跳过，不会删除。
     * <p>扫描按索引状态过滤，不做全表遍历。
     * <p>扫描范围包含 SQL 种子数据与运行期上传失败的文档。
     * 达到上限时输出一次告警。
     */
    private void indexPending() {
        // 一次查出全部待索引文档。
        // 逐个尝试，单个失败不影响其他文档。
        // 成功写回 indexed=true 并清除失败计数。
        // 一次查出全部待索引文档；数据量通常很小，避免分页带来的复杂度。
        List<KnowledgeDoc> pending = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .and(w -> w.eq(KnowledgeDoc::getIndexed, false)
                        .or().isNull(KnowledgeDoc::getIndexed)));
        // 逐个处理：单个文档失败不影响其余文档继续索引。
        for (KnowledgeDoc doc : pending) {
            // 已达上限的文档直接跳过，防止外部服务不可用时反复调用。
            if (indexAttempts.getOrDefault(doc.getId(), 0) >= MAX_INDEX_ATTEMPTS) {
                continue;
            }
            // 索引成功：写回数据库标记，居民问答即可检索到该文档。
            if (aiService.index(doc)) {
                // 只构造需要更新的字段，避免全量覆盖正文与标题。
                KnowledgeDoc update = new KnowledgeDoc();
                // 以主键定位文档。
                update.setId(doc.getId());
                // 索引成功标记，后续扫描不再选中该文档。
                update.setIndexed(true);
                // 持久化索引状态；若此处失败，下次扫描会重复索引，但索引本身幂等。
                knowledgeDocMapper.updateById(update);
                // 清理失败计数，避免内存中保留已成功文档的旧状态。
                indexAttempts.remove(doc.getId());
                log.info("知识库文档已建立向量索引: {}", doc.getTitle());
            } else {
                // 失败计数原子累加；ConcurrentHashMap.merge 保证并发调度下不丢更新。
                int attempts = indexAttempts.merge(doc.getId(), 1, Integer::sum);
                // 只在达到上限时告警一次，避免每次调度都刷同样的日志。
                if (attempts >= MAX_INDEX_ATTEMPTS) {
                    log.warn("知识库文档索引连续失败 {} 次，等待下次启动重试: {}", attempts, doc.getTitle());
                }
            }
        }
    }

    /**
     * 解析上传文件为纯文本。
     * <p>txt/md/csv 直接按 UTF-8 读取，保持原始排版；其他格式交给 Tika
     * <p>解析异常转换为业务错误，不暴露底层堆栈。
     * <p>纯文本格式直接按 UTF-8 读取，其他格式交给 Tika。
     * <p>解析在落库前完成，无法解析的文件不会产生数据库记录。
     * 解析 PDF、Word 等文档。任何解析异常都转换为 400，并提示支持的格式。
     *
     * @param file 上传文件
     * @return 解析出的纯文本
     * <p>解析异常不暴露底层堆栈，只提示支持的格式。
     * @throws BizException 文件无法解析时抛出
     */
    private String parse(MultipartFile file) {
        // 扩展名判断统一转小写。
        // 纯文本格式优先走轻量读取。
        // 其他格式由 Tika 解析后拼接文本。
        // txt/md/csv 直接按 UTF-8 读取。
        // 其他格式交给 Tika 解析。
        // 解析异常转换为 400 业务错误。
        // 文件名统一转小写后判断扩展名，避免大小写差异导致误判。
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            // 纯文本格式直接读取，避免 Tika 额外解析开销。
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                // 统一按 UTF-8 解码，与前端上传约定一致。
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            // 其他格式交给 Tika，支持 PDF、DOCX 等常见文档。
            List<org.springframework.ai.document.Document> docs =
                    // Tika 可能把一份文档拆成多个 Document，下方统一拼接为纯文本。
                    new TikaDocumentReader(new InputStreamResource(file.getInputStream())).get();
            // 拼接时保留换行，便于后续切块保持段落边界。
            return docs.stream().map(org.springframework.ai.document.Document::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
        // 解析异常不作为 500 抛出：多数情况是文件格式不受支持或文件损坏。
        } catch (Exception e) {
            // 记录简要原因，正文内容不写日志，避免敏感数据泄露。
            log.warn("知识库文档解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.BAD_REQUEST, "文档解析失败，请上传 txt/md/pdf/docx 格式");
        }
    }

}
