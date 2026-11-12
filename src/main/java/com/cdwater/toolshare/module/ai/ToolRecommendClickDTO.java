package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ToolRecommendClickDTO {

    @NotNull(message = "工具ID不能为空")
    private Long toolId;
}
