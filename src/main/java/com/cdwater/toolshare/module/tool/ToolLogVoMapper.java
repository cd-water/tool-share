package com.cdwater.toolshare.module.tool;

import org.mapstruct.Mapper;

/**
 * 工具日志实体到视图的 MapStruct 映射。
 * <p>字段同名直接映射，不做额外加工。
 */
@Mapper(componentModel = "spring")
public interface ToolLogVoMapper {

    /**
     * 转换工具日志视图。
     *
     * @param toolLog 日志实体
     * @return 日志视图
     */
    ToolLogVO toVO(ToolLog toolLog);
}
