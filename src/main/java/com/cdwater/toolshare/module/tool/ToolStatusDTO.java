package com.cdwater.toolshare.module.tool;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ToolStatusDTO {

    @NotBlank(message = "工具状态不能为空")
    private String status;
}
