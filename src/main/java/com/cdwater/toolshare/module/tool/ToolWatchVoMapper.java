package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface ToolWatchVoMapper {

    @Mapping(target = "toolId", source = "id")
    @Mapping(target = "statusName", ignore = true)
    ToolWatchVO toVO(Tool tool);

    @AfterMapping
    default void enrich(Tool tool, @MappingTarget ToolWatchVO vo) {
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        if (vo.getDeposit() == null) {
            vo.setDeposit(BigDecimal.ZERO);
        }
    }
}
