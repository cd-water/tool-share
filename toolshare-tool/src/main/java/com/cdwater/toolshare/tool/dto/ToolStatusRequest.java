package com.cdwater.toolshare.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ToolStatusRequest {

    @NotBlank(message = "状态不能为空")
    private String status;

    @NotBlank(message = "变更原因不能为空")
    private String reason;
}
