package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DamageVerifyDTO {

    /** CONFIRMED/SUSPECTED/FALSE */
    @NotBlank(message = "请选择核验结果")
    @Size(max = 20, message = "核验结果不能超过 20 个字符")
    private String verifyResult;

    @Size(max = 64, message = "维修负责人不能超过 64 个字符")
    private String repairPerson;

    @Size(max = 100, message = "预计时长不能超过 100 个字符")
    private String repairEstimate;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
