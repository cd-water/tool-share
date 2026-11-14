/**
 * 资金流水视图文件。
 * <p>用于租赁详情展示，包含流水类型编码与中文名、金额、经办人、说明与时间。
 * <h2>数据口径</h2>
 * <p>金额来自 t_payment_record，是资金事实；
 * 不使用租赁单上的金额快照，避免两者不一致时误导用户。
 * <p>调用方：RentalService#toPaymentVO。
 */
package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付流水：押金收取、退款与补收的资金事实。 */
@Data
public class PaymentRecordVO {

    // 流水主键
    private Long id;
    @Schema(allowableValues = {"DEPOSIT_IN", "DEPOSIT_REFUND", "RECEIVABLE"})
    // 流水类型编码
    private String recordType;
    // 流水类型中文名
    private String recordTypeName;
    // 金额
    private BigDecimal amount;
    // 经办人
    private String operatorName;
    // 流水说明
    private String remark;
    // 记录时间
    private LocalDateTime createTime;
}
