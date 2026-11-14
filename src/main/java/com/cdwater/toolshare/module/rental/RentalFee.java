/**
 * 租赁费用明细实体文件。
 * <p>对应 t_rental_fee 表，记录逾期费、损坏赔偿与减免。
 * <h2>设计原则</h2>
 * <p>减免单独成行（WAIVER），不修改原始费用金额；
 * 这样既能还原结算过程，也便于审计与对账。
 * <h2>字段</h2>
 * <p>feeType 取值 OVERDUE/DAMAGE/WAIVER/OTHER；
 * amount 保留两位小数；reason 记录费用说明或减免原因。
 * <p>调用方：RentalService#recordFee。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租赁费用明细实体，对应数据库表 t_rental_fee。
 * <p>记录逾期费、损坏赔偿与减免三类事实；减免单独成行，
 * 不修改原始费用金额，便于审计与对账。
 */
@Data
@TableName("t_rental_fee")
public class RentalFee {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属租赁单 id
    private Long rentalId;

    /** OVERDUE/DAMAGE/WAIVER/OTHER */
    private String feeType;

    // 费用或减免金额，统一保留两位小数
    private BigDecimal amount;

    // 费用说明；减免时包含减免原因
    private String reason;

    // 经办人用户名快照
    private String operatorName;

    // 记录时间，按升序还原结算过程
    private LocalDateTime createTime;
}
