package com.cdwater.toolshare.module.knowledge;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface KnowledgeDocVoMapper {

    @Mapping(target = "contentPreview", ignore = true)
    KnowledgeDocVO toVO(KnowledgeDoc doc);

    @AfterMapping
    default void preview(KnowledgeDoc doc, @MappingTarget KnowledgeDocVO vo) {
        String content = doc.getContent() == null ? "" : doc.getContent().replaceAll("\\s+", " ").trim();
        vo.setContentPreview(content.length() > 120 ? content.substring(0, 120) + "…" : content);
    }
}
