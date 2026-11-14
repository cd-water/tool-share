package com.cdwater.toolshare.module.knowledge;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * 知识库文档实体到视图对象的 MapStruct 映射器。
 * <p>content 不直接映射到视图对象，而是由 after-mapping 生成预览，
 * 避免把完整正文带到列表接口。
 */
@Mapper(componentModel = "spring")
public interface KnowledgeDocVoMapper {

    /**
     * 将文档实体映射为视图对象。
     * <p>contentPreview 需要压缩与截断，先忽略，映射后统一生成。
     *
     * @param doc 文档实体
     * @return 未生成预览的视图对象
     */
    @Mapping(target = "contentPreview", ignore = true)
    KnowledgeDocVO toVO(KnowledgeDoc doc);

    /**
     * 生成正文预览。
     * <p>先把连续空白压缩为单个空格，再截断到 120 个字符；这样列表中的
     * 预览长度稳定，也不会把换行、制表符原样带到前端。
     *
     * @param doc 源实体
     * @param vo  目标视图对象
     */
    @AfterMapping
    default void preview(KnowledgeDoc doc, @MappingTarget KnowledgeDocVO vo) {
        // 正文为空或仅含空白时预览为空串，前端可展示占位文案。
        String content = doc.getContent() == null ? "" : doc.getContent().replaceAll("\\s+", " ").trim();
        // 超长正文只保留前 120 个字符，并追加省略号提示仍有内容。
        vo.setContentPreview(content.length() > 120 ? content.substring(0, 120) + "…" : content);
    }
}
