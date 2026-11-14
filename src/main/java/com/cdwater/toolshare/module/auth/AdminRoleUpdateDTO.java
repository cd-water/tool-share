package com.cdwater.toolshare.module.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminRoleUpdateDTO {

    @NotBlank(message = "角色不能为空")
    @Size(max = 20, message = "角色不能超过 20 个字符")
    @Schema(allowableValues = {"STAFF", "SUPER_ADMIN"})
    private String role;
}
