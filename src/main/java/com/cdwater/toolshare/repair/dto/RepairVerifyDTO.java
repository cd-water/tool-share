package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报修核验入参
 */
@Data
public class RepairVerifyDTO {

    /** 核验结果：1确认损坏（需维修） 2疑似损坏（需检测） 3误报 */
    @NotNull(message = "核验结果不能为空")
    @Min(value = 1, message = "核验结果取值不正确")
    @Max(value = 3, message = "核验结果取值不正确")
    private Integer verifyResult;

    /** 核验说明 */
    @Size(max = 500, message = "核验说明最长 500 字")
    private String remark;
}
