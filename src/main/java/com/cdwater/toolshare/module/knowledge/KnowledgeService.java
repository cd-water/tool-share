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
public class KnowledgeService {

    /** 单次进程内索引最大尝试次数（含启动补建），耗尽后等下次启动再试。 */
    private static final int MAX_INDEX_ATTEMPTS = 3;

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final AiService aiService;
    private final KnowledgeDocVoMapper knowledgeDocVoMapper;

    /** 文档 id -> 已失败的索引尝试次数，成功或删除后移除。 */
    private final Map<Long, Integer> indexAttempts = new ConcurrentHashMap<>();

    public List<KnowledgeDocVO> list() {
        return knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                        .orderByDesc(KnowledgeDoc::getCreateTime))
                .stream().map(knowledgeDocVoMapper::toVO).toList();
    }

    public KnowledgeDocVO upload(MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择知识库文档");
        }
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "文档.txt";
        String content = parse(file);
        if (!StringUtils.hasText(content)) {
            throw new BizException(ResultCode.BAD_REQUEST, "文档内容为空或无法解析");
        }
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(StringUtils.hasText(title) ? title.trim() : fileName);
        doc.setFileName(fileName);
        doc.setContent(content.trim());
        doc.setIndexed(false);
        knowledgeDocMapper.insert(doc);
        doc.setIndexed(aiService.index(doc));
        knowledgeDocMapper.updateById(doc);
        if (!doc.getIndexed()) {
            indexAttempts.put(doc.getId(), 1);
            log.warn("知识库文档索引失败，已保留文档并等待重试: {}", doc.getTitle());
        }
        return knowledgeDocVoMapper.toVO(doc);
    }

    /**
     * 先清空向量 chunk 再删文档：向量清理失败时保留文档并抛出错误，避免已删除文档仍被检索。
     */
    public void delete(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ResultCode.NOT_FOUND, "知识库文档不存在");
        }
        if (!aiService.removeIndex(id)) {
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "向量索引清理失败，请稍后重试");
        }
        knowledgeDocMapper.deleteById(id);
        indexAttempts.remove(id);
    }

    /** 应用启动后为未建立向量索引的文档补建索引（含 SQL 种子数据）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void indexPendingDocs() {
        indexPending();
    }

    /** 运行期定时重试失败的索引，单进程内有限次，避免外部 AI 长期不可用时无限刷日志。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void retryPendingDocs() {
        indexPending();
    }

    private void indexPending() {
        List<KnowledgeDoc> pending = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .and(w -> w.eq(KnowledgeDoc::getIndexed, false)
                        .or().isNull(KnowledgeDoc::getIndexed)));
        for (KnowledgeDoc doc : pending) {
            if (indexAttempts.getOrDefault(doc.getId(), 0) >= MAX_INDEX_ATTEMPTS) {
                continue;
            }
            if (aiService.index(doc)) {
                KnowledgeDoc update = new KnowledgeDoc();
                update.setId(doc.getId());
                update.setIndexed(true);
                knowledgeDocMapper.updateById(update);
                indexAttempts.remove(doc.getId());
                log.info("知识库文档已建立向量索引: {}", doc.getTitle());
            } else {
                int attempts = indexAttempts.merge(doc.getId(), 1, Integer::sum);
                if (attempts >= MAX_INDEX_ATTEMPTS) {
                    log.warn("知识库文档索引连续失败 {} 次，等待下次启动重试: {}", attempts, doc.getTitle());
                }
            }
        }
    }

    private String parse(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            List<org.springframework.ai.document.Document> docs =
                    new TikaDocumentReader(new InputStreamResource(file.getInputStream())).get();
            return docs.stream().map(org.springframework.ai.document.Document::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
        } catch (Exception e) {
            log.warn("知识库文档解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.BAD_REQUEST, "文档解析失败，请上传 txt/md/pdf/docx 格式");
        }
    }

}
