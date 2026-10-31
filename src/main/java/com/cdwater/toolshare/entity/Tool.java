package com.cdwater.toolshare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_tool")
public class Tool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String model;

    /** WX/YY/QJ/QT */
    private String category;

    /** NEW/LIGHT/MEDIUM/SEVERE */
    private String damageLevel;

    /** AVAILABLE/RENTED/MAINTENANCE/SCRAPPED */
    private String status;

    private BigDecimal deposit;

    private String storageArea;

    private String usageGuide;

    private String imageUrl;

    /** 逗号分隔 */
    private String imageUrls;

    private LocalDate purchaseDate;

    private String supplier;

    private Integer auditStatus;

    private Integer rentalCount;

    private LocalDateTime lastRepairTime;

    private Long createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
