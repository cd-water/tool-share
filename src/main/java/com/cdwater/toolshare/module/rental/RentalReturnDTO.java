package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RentalReturnDTO {

    @NotBlank(message = "请选择验收结果")
    @Size(max = 16, message = "验收结果不能超过 16 个字符")
    private String damageResult;

    @DecimalMin(value = "0", message = "逾期费不能为负数")
    private BigDecimal overdueFee;

    @DecimalMin(value = "0", message = "损坏赔偿不能为负数")
    private BigDecimal damageFee;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
