/**
 * 租赁视图文件。
 * <p>用户端与管理端共用，包含租赁单快照、状态/押金状态/验收结果编码与中文名、
 * 工具主图、提醒字段，以及仅详情返回的费用与资金流水。
 * <h2>脱敏</h2>
 * <p>renterPhone 在映射阶段脱敏；身份证字段本身就是脱敏快照。
 * <h2>提醒字段</h2>
 * <p>remindBeforeMinutes 为 0 表示不提醒，null 表示未设置；
 * remindAt 是计划提醒时刻，可能为空。
 * <p>调用方：RentalService 的单条与分页映射。
 */
package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租赁视图，用户端与管理端共用。
 * <p>状态、押金状态与验收结果同时返回编码与中文名；
 * 手机号在映射阶段脱敏。费用与资金流水仅在详情接口返回。
 */
@Data
public class RentalVO {

    // 租赁单主键
    private Long id;
    // 租赁单号
    private String orderNo;
    // 工具 id
    private Long toolId;
    // 工具编号快照
    private String toolCode;
    // 工具名称快照
    private String toolName;
    /** 工具主图（MinIO 路径） */
    private String toolImageUrl;
    // 租用人 id
    private Long userId;
    // 租用人姓名
    private String renterName;
    // 脱敏手机号
    private String renterPhone;
    /** 身份证脱敏快照 */
    private String renterIdCardMasked;
    // 计划开始时间
    private LocalDateTime startTime;
    // 计划结束时间
    private LocalDateTime endTime;
    // 取件人
    private String pickupPerson;
    /** 录入人 */
    private String createdByName;
    /** 归还/取消经办人 */
    private String updatedByName;
    // 押金快照
    private BigDecimal depositAmount;
    @Schema(allowableValues = {"UNPAID", "PAID", "REFUNDED"})
    // 押金状态编码
    private String depositStatus;
    // 押金状态中文名
    private String depositStatusName;
    @Schema(allowableValues = {
            "PENDING_PAYMENT", "RESERVED", "PICKED_UP", "RETURNED", "CANCELLED", "OVERDUE"})
    // 租赁状态编码
    private String status;
    // 租赁状态中文名
    private String statusName;
    // 实际归还时间
    private LocalDateTime actualReturnTime;
    // 逾期费快照
    private BigDecimal overdueFee;
    // 损坏赔偿快照
    private BigDecimal damageFee;
    // 退还金额快照
    private BigDecimal refundAmount;
    @Schema(allowableValues = {"INTACT", "MINOR", "SEVERE"})
    // 验收结果编码
    private String damageResult;
    // 验收结果中文名
    private String damageResultName;
    // 归还备注
    private String returnRemark;
    // 取消原因
    private String cancelReason;
    // 取消时间
    private LocalDateTime cancelTime;
    /** 到期提醒提前量（分钟），0 表示不提醒，null 表示未设置 */
    private Integer remindBeforeMinutes;
    /** 计划提醒时刻 */
    private LocalDateTime remindAt;
    /** 费用明细（仅详情返回） */
    private List<RentalFeeVO> fees;
    /** 支付/退款流水（仅详情返回） */
    private List<PaymentRecordVO> payments;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;
}
