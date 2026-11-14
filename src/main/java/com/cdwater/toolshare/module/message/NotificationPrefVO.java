package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class NotificationPrefVO {

    @Schema(allowableValues = {
            "RESERVATION", "PICKUP", "RENT_DUE", "OVERDUE", "REPAIR", "TOOL_AVAILABLE",
            "ANNOUNCEMENT", "SYSTEM"})
    private String type;

    private String typeName;

    /** SYSTEM/SMS */
    @Schema(allowableValues = {"SYSTEM", "SMS"})
    private String channel;

    private Boolean enabled;
}
