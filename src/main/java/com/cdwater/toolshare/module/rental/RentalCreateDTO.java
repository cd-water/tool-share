package com.cdwater.toolshare.module.rental;

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
}
