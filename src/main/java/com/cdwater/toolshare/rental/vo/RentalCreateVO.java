package com.cdwater.toolshare.rental.vo;

import lombok.Data;

/**
 * 预约创建出参
 */
@Data
public class RentalCreateVO {

    private Long id;

    /** 预约单号 SQ-ZY-YYYYMMDD-XXXX */
    private String orderNo;
}
