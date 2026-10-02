package com.cdwater.toolshare.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;


@Data
@TableName("tool_info")
public class ToolInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String toolCode;

    private String name;

    private String model;

    private Long categoryId;

    private String description;

    private String damageLevel;

    private BigDecimal depositAmount;

    private String storageArea;

    private LocalDate purchaseDate;

    private String supplier;

    private Long creatorId;

    private String auditStatus;

    private String auditRemark;

    private String status;

    private Integer rentalCount;

    private LocalDateTime lastRepairTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
