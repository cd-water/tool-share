/**
 * 管理端工具列表查询条件文件。
 * <p>绑定 GET /api/admin/tools 的查询参数，包含：
 * 关键字、类别、状态、存放区域、排序方式、最低租赁次数、
 * 最近 N 天维修过、从未维修，以及分页参数。
 * <h2>排序白名单</h2>
 * <p>orderBy 只允许 default / rentalCount / lastRepairTime；
 * 服务层用 switch 白名单处理，绝不把参数拼进 SQL。
 * <h2>互斥条件</h2>
 * <p>neverRepaired 优先于 repairedWithinDays：同时传入时只按从未维修过滤。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端工具列表查询条件。
 * <p>排序 orderBy：default（创建时间倒序）/ rentalCount（租赁次数倒序）/ lastRepairTime（最近维修倒序）。
 * <p>筛选：minRentalCount（最低租赁次数）、repairedWithinDays（最近 N 天内维修过）、neverRepaired（从未维修）。
 */
@Data
public class ToolAdminQuery {

    // 关键字：同时匹配名称、型号、编号
    private String keyword;

    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    // 工具类别筛选，取值见 Dict.TOOL_CATEGORY
    private String category;

    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    // 工具状态筛选，取值见 ToolStatus
    private String status;

    // 存放区域模糊筛选
    private String storageArea;

    @Schema(allowableValues = {"default", "rentalCount", "lastRepairTime"})
    // 排序方式：default / rentalCount / lastRepairTime
    private String orderBy;

    // 最低累计租赁次数，为空不过滤
    private Integer minRentalCount;

    // 最近 N 天内维修过；与 neverRepaired 互斥，neverRepaired 优先
    private Integer repairedWithinDays;

    // true 时只返回 lastRepairTime 为空的工具
    private Boolean neverRepaired;

    // 页码，从 1 开始
    private long page = 1;

    // 每页条数，默认 10
    private long size = 10;
}
