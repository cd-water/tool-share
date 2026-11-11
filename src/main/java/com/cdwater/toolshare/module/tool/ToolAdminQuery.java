package com.cdwater.toolshare.module.tool;

import lombok.Data;

/**
 * 管理端工具列表查询条件。
 * <p>排序 orderBy：default（创建时间倒序）/ rentalCount（租赁次数倒序）/ lastRepairTime（最近维修倒序）。
 * <p>筛选：minRentalCount（最低租赁次数）、repairedWithinDays（最近 N 天内维修过）、neverRepaired（从未维修）。
 */
@Data
public class ToolAdminQuery {

    private String keyword;

    private String category;

    private String status;

    private String storageArea;

    private String orderBy;

    private Integer minRentalCount;

    private Integer repairedWithinDays;

    private Boolean neverRepaired;

    private long page = 1;

    private long size = 10;
}
