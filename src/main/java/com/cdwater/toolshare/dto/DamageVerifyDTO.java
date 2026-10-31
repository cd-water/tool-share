package com.cdwater.toolshare.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DamageVerifyDTO {

    /** CONFIRMED/SUSPECTED/FALSE */
    @NotBlank(message = "请选择核验结果")
    private String verifyResult;

    private String repairPerson;

    private String repairEstimate;

    private String remark;
}
