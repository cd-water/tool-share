package com.cdwater.toolshare.tool.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ToolItem {

    private Long id;

    private String toolCode;

    private String name;

    private String model;

    private Long categoryId;

    private String categoryName;

    private String coverImage;

    private String damageLevel;

    private BigDecimal depositAmount;

    private String status;

    private Integer rentalCount;

    private LocalDateTime lastRepairTime;

    private String auditStatus;
}
