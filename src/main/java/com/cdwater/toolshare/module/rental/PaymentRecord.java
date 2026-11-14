/**
 * 资金流水实体文件。
 * <p>对应 t_payment_record 表，记录押金收取、押金退还与超押金应收三类资金事实。
 * <h2>与租赁单的关系</h2>
 * <p>租赁单上的金额字段是展示快照；对账与审计以本表流水为准。
 * 流水按追加方式使用，不提供修改入口。
 * <h2>金额</h2>
 * <p>amount 统一保留两位小数，由 RentalService 写入时舍入。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金流水实体，对应数据库表 t_payment_record。
 * <p>记录押金收取、押金退还与超押金应收三类资金事实；
 * 租赁单上的金额字段只作展示快照，对账以本表为准。
 */
@Data
@TableName("t_payment_record")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属租赁单 id
    private Long rentalId;

    /** DEPOSIT_IN/DEPOSIT_REFUND/RECEIVABLE */
    private String recordType;

    // 资金金额，统一保留两位小数
    private BigDecimal amount;

    // 经办人用户名快照
    private String operatorName;

    // 流水说明，例如“押金支付”“归还验收后退还押金”
    private String remark;

    // 记录时间，按升序还原资金过程
    private LocalDateTime createTime;
}
