package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具录入审核入参
 */
@Data
public class ToolAuditDTO {

    /** 审核状态：2已通过 3需修改 */
    @NotNull(message = "审核状态不能为空")
    @Min(value = 2, message = "审核状态取值不正确")
    @Max(value = 3, message = "审核状态取值不正确")
    private Integer auditStatus;

    /** 修改意见（需修改时填写） */
    @Size(max = 200, message = "修改意见最长 200 字")
    private String auditOpinion;
}
