package com.cdwater.toolshare.repair.vo;

import lombok.Data;

/**
 * 报修创建出参
 */
@Data
public class RepairCreateVO {

    private Long id;

    /** 报修单号 SQ-BX-YYYYMMDD-XXXX */
    private String repairNo;
}
