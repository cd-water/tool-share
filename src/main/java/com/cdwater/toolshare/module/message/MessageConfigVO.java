package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;

public record MessageConfigVO(
        @Schema(allowableValues = {"SYSTEM", "SMS"}) String defaultChannel,
        Boolean smsEnabled) {
}
