package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维修验收入参
 */
@Data
public class RepairAcceptDTO {

    /** 验收结果：1合格（恢复可用） 2不合格（二次维修） 3部分合格（限用） */
    @NotNull(message = "验收结果不能为空")
    @Min(value = 1, message = "验收结果取值不正确")
    @Max(value = 3, message = "验收结果取值不正确")
    private Integer acceptResult;

    /** 验收后工具状态：1可用 3维修中（二次维修） 4报废 */
    @NotNull(message = "工具状态不能为空")
    @Min(value = 1, message = "工具状态取值不正确")
    @Max(value = 4, message = "工具状态取值不正确")
    private Integer toolStatus;

    /** 备注（费用分摊、赔偿说明、损坏程度重估） */
    @Size(max = 500, message = "备注最长 500 字")
    private String remark;
}
