/**
 * 工具审核请求体文件。
 * <p>审核结果只允许 APPROVED 或 NEEDS_CHANGE；
 * 退回（NEEDS_CHANGE）时 remark 必填，通过时可选。
 * <h2>幂等</h2>
 * <p>重复提交与原状态相同的审核结果时，服务层直接返回当前视图，
 * 不重复写日志、不重复同步索引。
 * <p>调用方：AdminToolController#audit。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具审核请求体。
 * <p>审核结果只能是 APPROVED 或 NEEDS_CHANGE；退回时必须同时提供审核意见。
 */
@Data
public class ToolAuditDTO {

    /** APPROVED / NEEDS_CHANGE */
    @NotBlank(message = "审核状态不能为空")
    @Size(max = 20, message = "审核状态不能超过 20 个字符")
    @Schema(allowableValues = {"APPROVED", "NEEDS_CHANGE"})
    // 审核结果编码：APPROVED / NEEDS_CHANGE
    private String auditStatus;

    @Size(max = 500, message = "审核意见不能超过 500 个字符")
    // 审核意见，退回场景必填
    private String remark;
}
