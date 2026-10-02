package com.cdwater.toolshare.tool.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class ToolDetail {

    private Long id;

    private String toolCode;

    private String name;

    private String model;

    private Long categoryId;

    private String categoryName;

    private String description;

    private String damageLevel;

    private BigDecimal depositAmount;

    private String storageArea;

    private LocalDate purchaseDate;

    private String supplier;

    private String status;

    private Integer rentalCount;

    private LocalDateTime lastRepairTime;

    private String auditStatus;

    private String auditRemark;

    private List<ToolImageItem> images;
}
