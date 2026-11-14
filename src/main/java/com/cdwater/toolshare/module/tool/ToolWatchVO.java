/**
 * 蹲守列表项视图文件。
 * <p>展示用户蹲守工具的关键信息：工具标识、名称、当前状态、押金与主图。
 * 状态名称由映射器翻译；押金为空时补 0，避免前端空值展示。
 * <h2>数据来源</h2>
 * <p>工具 id 来自 Redis 用户侧集合，其余字段读取数据库当前值，
 * 因此列表反映的是最新状态而不是蹲守时的快照。
 * <p>调用方：ToolWatchService#watchedList。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 蹲守列表项 */
@Data
public class ToolWatchVO {

    // 工具 id
    private Long toolId;
    // 工具编号
    private String code;
    // 工具名称
    private String name;
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    // 当前工具状态编码
    private String status;
    // 工具状态中文名
    private String statusName;
    // 押金；为空时映射器补 0
    private BigDecimal deposit;
    // 主图地址
    private String imageUrl;
}
