package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具状态更新入参
 */
@Data
public class ToolStatusDTO {

    /** 状态：1可用 2已借出 3维修中 4报废 5下架 */
    @NotNull(message = "工具状态不能为空")
    @Min(value = 1, message = "工具状态取值不正确")
    @Max(value = 5, message = "工具状态取值不正确")
    private Integer status;

    /** 备注 */
    @Size(max = 200, message = "备注最长 200 字")
    private String remark;
}
