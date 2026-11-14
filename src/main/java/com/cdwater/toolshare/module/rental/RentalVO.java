package com.cdwater.toolshare.module.rental;

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
    /** 工具主图（MinIO 路径） */
    private String toolImageUrl;
    private Long userId;
    private String renterName;
    private String renterPhone;
    /** 身份证脱敏快照 */
    private String renterIdCardMasked;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String pickupPerson;
    /** 录入人 */
    private String createdByName;
    /** 归还/取消经办人 */
    private String updatedByName;
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
    /** 到期提醒提前量（分钟），0 表示不提醒，null 表示未设置 */
    private Integer remindBeforeMinutes;
    /** 计划提醒时刻 */
    private LocalDateTime remindAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
