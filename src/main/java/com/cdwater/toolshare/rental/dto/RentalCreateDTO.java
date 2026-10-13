package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建预约入参
 */
@Data
public class RentalCreateDTO {

    /** 工具 id */
    @NotNull(message = "工具不能为空")
    private Long toolId;

    /** 租赁开始时间 */
    @NotNull(message = "租赁开始时间不能为空")
    private LocalDateTime startTime;

    /** 租赁结束时间 */
    @NotNull(message = "租赁结束时间不能为空")
    private LocalDateTime endTime;

    /** 取件人（可为代取人） */
    @NotBlank(message = "取件人不能为空")
    @Size(max = 50, message = "取件人最长 50 字")
    private String pickupName;
}
