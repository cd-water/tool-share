package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_rental")
public class Rental {

    @TableId(type = IdType.AUTO)
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

    /** UNPAID/PAID/REFUNDED */
    private String depositStatus;

    /** PENDING_PAYMENT/RESERVED/PICKED_UP/RETURNED/CANCELLED/OVERDUE */
    private String status;

    private LocalDateTime actualReturnTime;

    private BigDecimal overdueFee;

    private BigDecimal damageFee;

    private BigDecimal refundAmount;

    /** INTACT/MINOR/SEVERE */
    private String damageResult;

    private String returnRemark;

    private String cancelReason;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
