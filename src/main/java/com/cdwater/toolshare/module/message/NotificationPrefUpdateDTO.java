package com.cdwater.toolshare.module.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NotificationPrefUpdateDTO(
        @NotNull Boolean enabled,
        @NotBlank String channel) {
}
