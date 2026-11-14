package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DamageAcceptDTO {

    /** QUALIFIED/UNQUALIFIED/PARTIAL */
    @NotBlank(message = "请选择验收结果")
    @Size(max = 20, message = "验收结果不能超过 20 个字符")
    private String acceptResult;

    /** AVAILABLE/MAINTENANCE/SCRAPPED */
    @NotBlank(message = "请选择验收后工具状态")
    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    private String toolStatusAfter;

    @DecimalMin(value = "0", message = "维修费用不能为负数")
    private BigDecimal repairCost;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
