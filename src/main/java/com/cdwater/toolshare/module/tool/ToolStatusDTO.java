package com.cdwater.toolshare.module.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ToolStatusDTO {

    @NotBlank(message = "工具状态不能为空")
    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    private String status;
}
