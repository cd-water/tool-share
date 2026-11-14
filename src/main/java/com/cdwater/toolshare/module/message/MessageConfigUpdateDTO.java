package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MessageConfigUpdateDTO(
        @NotBlank @Schema(allowableValues = {"SYSTEM", "SMS"}) String defaultChannel,
        @NotNull Boolean smsEnabled) {
}
