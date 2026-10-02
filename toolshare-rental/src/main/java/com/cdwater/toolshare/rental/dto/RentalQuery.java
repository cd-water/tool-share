package com.cdwater.toolshare.rental.dto;

import lombok.Data;

/**
 * 员工端租赁记录筛选（可导出，见 docs/API.md）
 */
@Data
public class RentalQuery {

    private long page = 1;

    private long size = 10;

    private String userName;

    private String toolCode;

    private String acceptResult;

    private String status;

    /** 仅看逾期 */
    private Boolean overdue;
}
