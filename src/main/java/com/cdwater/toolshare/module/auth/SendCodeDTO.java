package com.cdwater.toolshare.module.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendCodeDTO {

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    @Size(max = 20)
    private String phone;
}
