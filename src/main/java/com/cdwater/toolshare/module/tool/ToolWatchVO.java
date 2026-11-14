package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 蹲守列表项 */
@Data
public class ToolWatchVO {

    private Long toolId;
    private String code;
    private String name;
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    private String status;
    private String statusName;
    private BigDecimal deposit;
    private String imageUrl;
}
