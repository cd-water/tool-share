package com.cdwater.ticketbox.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminLoginDTO {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
