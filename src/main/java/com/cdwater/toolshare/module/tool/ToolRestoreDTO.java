package com.cdwater.toolshare.module.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ToolRestoreDTO {

    @NotBlank(message = "恢复原因不能为空")
    @Size(max = 500, message = "恢复原因不能超过 500 个字符")
    private String reason;
}
