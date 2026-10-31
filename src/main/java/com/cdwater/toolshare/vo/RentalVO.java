package com.cdwater.toolshare.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RentalVO {

    private Long id;
    private String orderNo;
    private Long toolId;
    private String toolCode;
    private String toolName;
    private Long userId;
    private String renterName;
    private String renterPhone;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String pickupPerson;
    private BigDecimal depositAmount;
    private String depositStatus;
    private String depositStatusName;
    private String status;
    private String statusName;
    private LocalDateTime actualReturnTime;
    private BigDecimal overdueFee;
    private BigDecimal damageFee;
    private BigDecimal refundAmount;
    private String damageResult;
    private String damageResultName;
    private String returnRemark;
    private String cancelReason;
    private LocalDateTime cancelTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
