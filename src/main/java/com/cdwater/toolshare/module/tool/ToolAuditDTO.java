package com.cdwater.toolshare.module.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ToolAuditDTO {

    /** APPROVED / NEEDS_CHANGE */
    @NotBlank(message = "审核状态不能为空")
    @Size(max = 20, message = "审核状态不能超过 20 个字符")
    private String auditStatus;

    @Size(max = 500, message = "审核意见不能超过 500 个字符")
    private String remark;
}
