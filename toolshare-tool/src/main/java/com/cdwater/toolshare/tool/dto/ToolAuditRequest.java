package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 录入审核：APPROVED / REJECTED（REJECTED 时 auditRemark 必填，服务端校验）
 */
@Data
public class ToolAuditRequest {

    @NotBlank(message = "审核结果不能为空")
    private String result;

    private String auditRemark;
}
