package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RentalCreateDTO {

    @NotNull(message = "请选择工具")
    private Long toolId;

    @NotNull(message = "请选择开始时间")
    private LocalDateTime startTime;

    @NotNull(message = "请选择结束时间")
    private LocalDateTime endTime;

    @NotBlank(message = "请填写取件人")
    private String pickupPerson;

    /**
     * 到期提醒：提前多少分钟提醒，0 表示不提醒；为空时使用配置默认值（1440 分钟）。
     */
    @Min(value = 0, message = "提醒提前量不能为负数")
    @Max(value = 10080, message = "提醒提前量不能超过 7 天")
    private Integer remindBeforeMinutes;
}
