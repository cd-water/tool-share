package com.cdwater.toolshare.module.rental;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租赁费用明细：逾期费、损坏赔偿与减免。 */
@Data
public class RentalFeeVO {

    private Long id;
    @Schema(allowableValues = {"OVERDUE", "DAMAGE", "WAIVER", "OTHER"})
    private String feeType;
    private String feeTypeName;
    private BigDecimal amount;
    private String reason;
    private String operatorName;
    private LocalDateTime createTime;
}
