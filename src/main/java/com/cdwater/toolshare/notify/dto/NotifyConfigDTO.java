package com.cdwater.toolshare.notify.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 默认提醒方式配置（读/写共用）
 */
@Data
public class NotifyConfigDTO {

    /** 渠道：1系统内 2短信 */
    @NotNull(message = "提醒方式不能为空")
    @Min(value = 1, message = "提醒方式取值不正确")
    @Max(value = 2, message = "提醒方式取值不正确")
    private Integer channel;
}
