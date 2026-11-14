package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminLoginDTO {

    @NotBlank
    @Size(max = 20, message = "账号不能超过 20 个字符")
    private String username;

    @NotBlank
    @Size(max = 100, message = "密码不能超过 100 个字符")
    private String password;
}
