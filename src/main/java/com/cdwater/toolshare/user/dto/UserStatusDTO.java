package com.cdwater.toolshare.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用/停用入参
 */
@Data
public class UserStatusDTO {

    /** 1启用 0停用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态取值不正确")
    @Max(value = 1, message = "状态取值不正确")
    private Integer status;
}
