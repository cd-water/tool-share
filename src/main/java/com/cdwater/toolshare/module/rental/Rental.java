/**
 * 租赁单实体文件。
 * <p>对应 t_rental 表，是租赁生命周期的核心对象。
 * <h2>状态</h2>
 * <p>PENDING_PAYMENT/RESERVED/PICKED_UP/RETURNED/CANCELLED/OVERDUE，
 * 由 RentalStateMachine 约束；所有更新都带原状态条件与乐观锁 version。
 * <h2>快照字段</h2>
 * <p>工具编号/名称、租用人姓名/手机号/脱敏身份证在创建时固化，
 * 后续资料变更不影响历史单据。
 * <h2>金额</h2>
 * <p>押金在创建时快照；逾期费、赔偿、退款在归还时写入。
 * 资金事实在 t_payment_record，费用明细在 t_rental_fee。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租赁单实体，对应数据库表 t_rental。
 * <p>状态流转由 {@link RentalStateMachine} 约束，所有更新都带原状态条件与乐观锁 version。
 * <p>金额字段是展示快照：押金在创建时快照，逾期费/赔偿/退款在归还时写入；
 * 真正的资金事实在 t_payment_record，费用明细在 t_rental_fee。
 * <p>租用人姓名、手机号与脱敏身份证是下单时快照，后续用户资料变更不影响历史单据。
 */
@Data
@TableName("t_rental")
public class Rental {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 业务单号，全局唯一，对外展示与消息文案使用
    private String orderNo;

    // 工具 id
    private Long toolId;

    // 工具编号快照，工具资料变更后历史单仍保持原值
    private String toolCode;

    // 工具名称快照
    private String toolName;

    // 租用人用户 id，用于所有权校验
    private Long userId;

    // 租用人姓名快照
    private String renterName;

    // 租用人手机号快照；对外展示与导出时脱敏
    private String renterPhone;

    /** 身份证脱敏快照，仅保存 mask 后的值 */
    private String renterIdCardMasked;

    // 计划租期开始
    private LocalDateTime startTime;

    // 计划租期结束，用于取件提醒与逾期判断
    private LocalDateTime endTime;

    // 实际取件人
    private String pickupPerson;

    /** 录入人 */
    private String createdByName;

    /** 归还/取消经办人 */
    private String updatedByName;

    // 押金快照，归还结算以此封顶
    private BigDecimal depositAmount;

    /** UNPAID/PAID/REFUNDED */
    private String depositStatus;

    /** PENDING_PAYMENT/RESERVED/PICKED_UP/RETURNED/CANCELLED/OVERDUE */
    private String status;

    /** 乐观锁版本号，所有状态流转原子更新时自增 */
    @Version
    private Integer version;

    // 实际归还时间，归还前为空
    private LocalDateTime actualReturnTime;

    // 逾期费快照（减免前）
    private BigDecimal overdueFee;

    // 损坏赔偿快照（减免前）
    private BigDecimal damageFee;

    // 押金退还金额快照
    private BigDecimal refundAmount;

    /** INTACT/MINOR/SEVERE */
    private String damageResult;

    // 归还备注；带伤归还时记录原因
    private String returnRemark;

    // 取消原因；系统取消时写入固定文案
    private String cancelReason;

    // 取消时间
    private LocalDateTime cancelTime;

    // 创建时间，列表默认排序字段
    private LocalDateTime createTime;

    // 最近更新时间
    private LocalDateTime updateTime;
}
