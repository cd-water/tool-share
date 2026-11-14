package com.cdwater.toolshare.module.tool;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ToolLogVoMapper {

    ToolLogVO toVO(ToolLog toolLog);
}
