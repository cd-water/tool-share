package com.cdwater.toolshare.rental.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租赁单表（预约 → 取件 → 归还全生命周期单表，状态机区分阶段）
 */
@Data
@TableName("rental_order")
public class RentalOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 预约单号：SQ-ZY-YYYYMMDD-XXXX */
    private String orderNo;

    /** 工具 id */
    private Long toolId;

    /** 租赁人 user.id */
    private Long userId;

    /** 取件人（可为代取人） */
    private String pickupName;

    /** 租赁开始时间 */
    private LocalDateTime startTime;

    /** 租赁结束时间 */
    private LocalDateTime endTime;

    /** 押金金额（元） */
    private BigDecimal depositAmount;

    /** 押金状态：1未缴 2已缴 3已退还（支付沙箱） */
    private Integer depositStatus;

    /** 状态：1待支付 2已预约 3已取件 4已归还 5已取消 */
    private Integer status;

    /** 取消时间（取件前 24 小时可取消） */
    private LocalDateTime cancelTime;

    /** 实际归还时间 */
    private LocalDateTime returnTime;

    /** 归还验收结果：1完好 2轻微损坏（无需赔偿） 3严重损坏（需赔偿） */
    private Integer acceptResult;

    /** 逾期费用（元，封顶押金） */
    private BigDecimal overdueFee;

    /** 押金实退金额（扣除逾期费/赔偿） */
    private BigDecimal depositRefundAmount;

    /** 更新人 user.id（归还补充记录的工作人员） */
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
