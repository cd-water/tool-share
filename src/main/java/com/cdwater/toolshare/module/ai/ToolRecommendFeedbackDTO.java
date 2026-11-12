package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ToolRecommendFeedbackDTO {

    /** 1 有帮助 2 没帮助 */
    @NotNull(message = "请选择反馈结果")
    @Min(value = 1, message = "无效的反馈结果")
    @Max(value = 2, message = "无效的反馈结果")
    private Integer feedback;
}
