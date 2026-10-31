package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.entity.KnowledgeDoc;
import com.cdwater.toolshare.mapper.KnowledgeDocMapper;
import com.cdwater.toolshare.vo.KnowledgeDocVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final AiService aiService;

    public List<KnowledgeDocVO> list() {
        return knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                        .orderByDesc(KnowledgeDoc::getCreateTime))
                .stream().map(this::toVO).toList();
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
        return toVO(doc);
    }

    public void delete(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ResultCode.NOT_FOUND, "知识库文档不存在");
        }
        knowledgeDocMapper.deleteById(id);
        aiService.removeIndex(doc);
    }

    /**
     * 应用启动后为未建立向量索引的文档补建索引（含 SQL 种子数据）。
     * 外部 AI 服务不可用时保持 indexed=0，下次启动自动重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void indexPendingDocs() {
        List<KnowledgeDoc> pending = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .and(w -> w.eq(KnowledgeDoc::getIndexed, false)
                        .or().isNull(KnowledgeDoc::getIndexed)));
        for (KnowledgeDoc doc : pending) {
            if (aiService.index(doc)) {
                KnowledgeDoc update = new KnowledgeDoc();
                update.setId(doc.getId());
                update.setIndexed(true);
                knowledgeDocMapper.updateById(update);
                log.info("知识库文档已建立向量索引: {}", doc.getTitle());
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

    private KnowledgeDocVO toVO(KnowledgeDoc doc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        vo.setId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setFileName(doc.getFileName());
        String content = doc.getContent() == null ? "" : doc.getContent().replaceAll("\\s+", " ").trim();
        vo.setContentPreview(content.length() > 120 ? content.substring(0, 120) + "…" : content);
        vo.setCreateTime(doc.getCreateTime());
        return vo;
    }
}
