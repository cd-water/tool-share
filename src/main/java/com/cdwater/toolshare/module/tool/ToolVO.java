package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ToolVO {

    private Long id;
    private String code;
    private String name;
    private String model;
    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    private String category;
    private String categoryName;
    @Schema(allowableValues = {"NEW", "LIGHT", "MEDIUM", "SEVERE"})
    private String damageLevel;
    private String damageLevelName;
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    private String status;
    private String statusName;
    private BigDecimal deposit;
    private String storageArea;
    private String usageGuide;
    private String imageUrl;
    private List<String> imageUrls;
    private LocalDate purchaseDate;
    private String supplier;
    private Integer auditStatus;
    private String auditStatusName;
    private String auditRemark;
    private Long createdBy;
    private String createdByName;
    private Integer rentalCount;
    /** 平均分（一位小数），无评价时为 0.0 */
    private BigDecimal ratingAvg;
    /** 评价数量 */
    private Long reviewCount;
    private LocalDateTime lastRepairTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
