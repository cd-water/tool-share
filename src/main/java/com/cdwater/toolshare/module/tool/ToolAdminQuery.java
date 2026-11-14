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

    private String keyword;

    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    private String category;

    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    private String status;

    private String storageArea;

    @Schema(allowableValues = {"default", "rentalCount", "lastRepairTime"})
    private String orderBy;

    private Integer minRentalCount;

    private Integer repairedWithinDays;

    private Boolean neverRepaired;

    private long page = 1;

    private long size = 10;
}
