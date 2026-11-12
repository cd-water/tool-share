package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ToolRecommendDTO {

    @NotBlank(message = "请描述您的需求")
    @Size(max = 500, message = "需求描述不能超过 500 字")
    private String question;

    /** 期望开始时间，与 endTime 同时为空或同时填写 */
    private LocalDateTime startTime;

    /** 期望结束时间 */
    private LocalDateTime endTime;

    private String category;

    @DecimalMin(value = "0", message = "押金上限不能为负数")
    private BigDecimal maxDeposit;

    @Min(value = 1, message = "推荐数量至少为 1")
    @Max(value = 5, message = "推荐数量最多为 5")
    private Integer limit = 5;
}
