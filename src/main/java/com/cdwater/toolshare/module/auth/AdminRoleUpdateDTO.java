package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminRoleUpdateDTO {

    @NotBlank(message = "角色不能为空")
    @Size(max = 20, message = "角色不能超过 20 个字符")
    private String role;
}
