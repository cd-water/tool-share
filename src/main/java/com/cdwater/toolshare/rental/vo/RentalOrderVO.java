package com.cdwater.toolshare.rental.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租赁单出参（附录租赁记录单，含工具/租赁人关联信息）
 */
@Data
public class RentalOrderVO {

    private Long id;

    /** 预约单号 SQ-ZY-YYYYMMDD-XXXX */
    private String orderNo;

    private Long toolId;

    /** 工具编号（关联带出） */
    private String toolCode;

    /** 工具名称（关联带出） */
    private String toolName;

    private Long userId;

    /** 租赁人姓名（关联带出） */
    private String userName;

    /** 租赁人联系方式（脱敏） */
    private String userPhone;

    /** 取件人 */
    private String pickupName;

    /** 租赁开始时间 */
    private LocalDateTime startTime;

    /** 租赁结束时间 */
    private LocalDateTime endTime;

    /** 押金金额（元） */
    private BigDecimal depositAmount;

    /** 押金状态：1未缴 2已缴 3已退还 */
    private Integer depositStatus;

    /** 状态：1待支付 2已预约 3已取件 4已归还 5已取消 */
    private Integer status;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 实际归还时间 */
    private LocalDateTime returnTime;

    /** 归还验收结果：1完好 2轻微损坏 3严重损坏 */
    private Integer acceptResult;

    /** 逾期费用（元） */
    private BigDecimal overdueFee;

    /** 押金实退金额（元） */
    private BigDecimal depositRefundAmount;

    /** 创建时间（录入时间） */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
