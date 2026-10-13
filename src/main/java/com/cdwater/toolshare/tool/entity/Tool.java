package com.cdwater.toolshare.tool.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具表
 */
@Data
@TableName(value = "tool", autoResultMap = true)
public class Tool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具编号：SQ-G-XX-YYYY（XX=WX维修/YY园艺/QJ清洁/QT其他） */
    private String toolCode;

    /** 工具名称 */
    private String name;

    /** 型号规格 */
    private String modelSpec;

    /** 类别：1维修 2园艺 3清洁 4其他 */
    private Integer category;

    /** 损坏程度：1全新 2轻微磨损 3中度磨损 4严重损坏（归还验收后重估） */
    private Integer damageLevel;

    /** 押金金额（元） */
    private BigDecimal deposit;

    /** 存放区域：社区服务站+柜体编号 */
    private String storageLocation;

    /** 使用说明（图文富文本） */
    private String usageInstruction;

    /** 工具图片 URL 数组（MinIO，JSON 列） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 采购日期 */
    private LocalDate purchaseDate;

    /** 供应商信息 */
    private String supplierInfo;

    /** 状态：1可用 2已借出 3维修中 4报废 5下架 */
    private Integer status;

    /** 审核状态：1待审核 2已通过 3需修改 */
    private Integer auditStatus;

    /** 审核修改意见（auditStatus=3 时填写） */
    private String auditOpinion;

    /** 录入人 user.id */
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
