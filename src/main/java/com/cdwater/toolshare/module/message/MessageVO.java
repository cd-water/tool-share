package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;
    @Schema(allowableValues = {
            "RESERVATION", "PICKUP", "RENT_DUE", "OVERDUE", "REPAIR", "TOOL_AVAILABLE",
            "ANNOUNCEMENT", "SYSTEM"})
    private String type;
    private String typeName;
    private String title;
    private String content;
    @Schema(allowableValues = {"SYSTEM", "SMS"})
    private String channel;
    @Schema(allowableValues = {"PENDING", "SENT", "DELIVERED", "FAILED"})
    private String deliveryStatus;
    private LocalDateTime sentTime;
    private Boolean readStatus;
    private LocalDateTime createTime;
}
