/**
 * 蹲守工具实体到视图的 MapStruct 映射文件。
 * <p>把 Tool.id 映射为 toolId，并在后置处理中补状态中文名与押金默认值。
  * <h2>字段映射</h2>
  * <p>Tool.id -> toolId；deposit 为空时补 0；
  * statusName 通过 Dict.TOOL_STATUS 翻译。其余字段同名映射。
  * <p>工具已删除时不会进入映射流程，由 Service 过滤。
 * <h2>约束</h2>
 * <p>只做展示转换，不改变工具状态；工具已被删除时由 Service 过滤。
 * <p>调用方：ToolWatchService#watchedList。
 */
package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.math.BigDecimal;

/**
 * 蹲守工具实体到视图的 MapStruct 映射。
 * <p>工具 id 映射为 toolId；状态名称与押金默认值在后置处理中补齐。
 */
@Mapper(componentModel = "spring")
public interface ToolWatchVoMapper {

    @Mapping(target = "toolId", source = "id")
    @Mapping(target = "statusName", ignore = true)
    /**
     * 转换蹲守列表项。
     *
     * @param tool 工具实体
     * @return 蹲守视图
     */
    ToolWatchVO toVO(Tool tool);

    // 后置映射：补状态中文名；押金为空时归零，避免前端空值展示
    @AfterMapping
    default void enrich(Tool tool, @MappingTarget ToolWatchVO vo) {
        // 状态编码翻译为中文名
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        // 押金为空时补 0，避免前端空值展示
        if (vo.getDeposit() == null) {
            vo.setDeposit(BigDecimal.ZERO);
        }
    }
}
