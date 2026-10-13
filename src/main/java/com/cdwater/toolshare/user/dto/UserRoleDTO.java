package com.cdwater.toolshare.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改角色入参
 */
@Data
public class UserRoleDTO {

    /** 角色：0居民 1社区工作人员 2系统管理员 */
    @NotNull(message = "角色不能为空")
    @Min(value = 0, message = "角色取值不正确")
    @Max(value = 2, message = "角色取值不正确")
    private Integer role;
}
