package com.cdwater.toolshare.module.tool;

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
    private String category;
    private String categoryName;
    private String damageLevel;
    private String damageLevelName;
    private String status;
    private String statusName;
    private BigDecimal deposit;
    private String storageArea;
    private String usageGuide;
    private String imageUrl;
    private List<String> imageUrls;
    private LocalDate purchaseDate;
    private String supplier;
    private Integer rentalCount;
    private LocalDateTime lastRepairTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
