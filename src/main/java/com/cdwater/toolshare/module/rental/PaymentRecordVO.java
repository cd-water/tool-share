package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付流水：押金收取、退款与补收的资金事实。 */
@Data
public class PaymentRecordVO {

    private Long id;
    @Schema(allowableValues = {"DEPOSIT_IN", "DEPOSIT_REFUND", "RECEIVABLE"})
    private String recordType;
    private String recordTypeName;
    private BigDecimal amount;
    private String operatorName;
    private String remark;
    private LocalDateTime createTime;
}
