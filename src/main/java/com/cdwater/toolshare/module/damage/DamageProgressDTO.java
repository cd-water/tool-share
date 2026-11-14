package com.cdwater.toolshare.module.damage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DamageProgressDTO {

    /** WAITING_PARTS/REPAIRING/COMPLETED/FAILED */
    @NotBlank(message = "请选择维修进度")
    @Size(max = 20, message = "维修进度不能超过 20 个字符")
    @Schema(allowableValues = {"WAITING_PARTS", "REPAIRING", "COMPLETED", "FAILED"})
    private String progressStatus;

    @NotBlank(message = "请填写进度说明")
    @Size(max = 500, message = "进度说明不能超过 500 个字符")
    private String progressNote;

    @DecimalMin(value = "0", message = "维修费用不能为负数")
    private BigDecimal repairCost;

    @Size(max = 100, message = "预计时长不能超过 100 个字符")
    private String repairEstimate;

    @Size(max = 64, message = "维修负责人不能超过 64 个字符")
    private String repairPerson;
}
