package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CodeLoginDTO {

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    private String phone;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$")
    private String code;
}
