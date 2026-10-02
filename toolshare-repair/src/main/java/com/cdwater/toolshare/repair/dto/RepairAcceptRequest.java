package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 修后验收：PASS / FAIL / PARTIAL（联动工具状态与损坏重估）
 */
@Data
public class RepairAcceptRequest {

    @NotBlank(message = "验收结果不能为空")
    private String result;

    @NotBlank(message = "维修说明不能为空")
    private String repairDescription;

    private BigDecimal repairFee;

    @NotBlank(message = "重估损坏程度不能为空")
    private String damageLevel;
}
