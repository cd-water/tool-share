package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ToolVoMapper {

    @Mapping(target = "categoryName", ignore = true)
    @Mapping(target = "damageLevelName", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "imageUrls", ignore = true)
    ToolVO toVO(Tool tool);

    @AfterMapping
    default void enrich(Tool tool, @MappingTarget ToolVO vo) {
        vo.setCategoryName(Dict.label(Dict.TOOL_CATEGORY, tool.getCategory()));
        vo.setDamageLevelName(Dict.label(Dict.DAMAGE_LEVEL, tool.getDamageLevel()));
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        vo.setImageUrls(parseImages(tool.getImageUrls()));
    }

    private List<String> parseImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
