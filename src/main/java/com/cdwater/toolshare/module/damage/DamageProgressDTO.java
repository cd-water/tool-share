package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DamageProgressDTO {

    /** WAITING_PARTS/REPAIRING/COMPLETED/FAILED */
    @NotBlank(message = "请选择维修进度")
    private String progressStatus;

    @NotBlank(message = "请填写进度说明")
    private String progressNote;

    private BigDecimal repairCost;

    private String repairEstimate;

    private String repairPerson;
}
