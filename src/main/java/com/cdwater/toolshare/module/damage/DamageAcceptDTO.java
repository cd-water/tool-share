package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DamageAcceptDTO {

    /** QUALIFIED/UNQUALIFIED/PARTIAL */
    @NotBlank(message = "请选择验收结果")
    private String acceptResult;

    /** AVAILABLE/MAINTENANCE/SCRAPPED */
    @NotBlank(message = "请选择验收后工具状态")
    private String toolStatusAfter;

    private BigDecimal repairCost;

    private String remark;
}
