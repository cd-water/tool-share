package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RentalCreateRequest {

    @NotNull(message = "工具不能为空")
    private Long toolId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 取件人，默认本人 */
    private String pickupPerson;
}
