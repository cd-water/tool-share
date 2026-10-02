package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 归还验收（SEVERE_DAMAGED 时 compensationFee 必填 >0，服务端校验）
 */
@Data
public class ReturnRequest {

    @NotBlank(message = "验收结果不能为空")
    private String acceptResult;

    @NotBlank(message = "重估损坏程度不能为空")
    private String damageLevel;

    @DecimalMin(value = "0", message = "赔偿金额不能为负")
    private BigDecimal compensationFee;
}
