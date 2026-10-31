package com.cdwater.toolshare.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RentalReturnDTO {

    @NotBlank(message = "请选择验收结果")
    private String damageResult;

    private BigDecimal overdueFee;

    private BigDecimal damageFee;

    private String remark;
}
