/**
 * 工具视图文件。
 * <p>用户端与管理端共用：管理端可见审核信息与创建人，用户端是否可见由服务层过滤。
 * <h2>字段约定</h2>
 * <p>字典字段同时返回编码与中文名，减少前端字典依赖；
 * imageUrls 由逗号分隔串解析为列表；
 * ratingAvg 保留一位小数，无评价为 0.0，reviewCount 为 0。
 * <p>调用方：ToolService 的单条与分页映射。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具视图。
 * <p>同时在用户端与管理端使用：管理端可见审核信息与创建人，
 * 用户端是否可见由服务层在查询前按审核状态过滤。
 * 字典字段同时返回编码与中文名称，减少前端字典依赖。
 */
@Data
public class ToolVO {

    // 工具主键
    private Long id;
    // 业务编号
    private String code;
    // 工具名称
    private String name;
    // 型号规格
    private String model;
    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    // 类别编码
    private String category;
    // 类别中文名
    private String categoryName;
    @Schema(allowableValues = {"NEW", "LIGHT", "MEDIUM", "SEVERE"})
    // 损坏程度编码
    private String damageLevel;
    // 损坏程度中文名
    private String damageLevelName;
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    // 工具状态编码
    private String status;
    // 工具状态中文名
    private String statusName;
    // 押金金额
    private BigDecimal deposit;
    // 存放区域
    private String storageArea;
    // 使用说明（已清洗）
    private String usageGuide;
    // 主图地址
    private String imageUrl;
    // 全部图片地址
    private List<String> imageUrls;
    // 采购日期
    private LocalDate purchaseDate;
    // 供应商
    private String supplier;
    // 审核状态数值：0 待审核 1 通过 2 需修改
    private Integer auditStatus;
    // 审核状态中文名
    private String auditStatusName;
    // 审核意见
    private String auditRemark;
    // 创建人账号 id
    private Long createdBy;
    // 创建人用户名
    private String createdByName;
    // 累计租赁次数
    private Integer rentalCount;
    /** 平均分（一位小数），无评价时为 0.0 */
    private BigDecimal ratingAvg;
    /** 评价数量 */
    private Long reviewCount;
    // 最近恢复可用时间
    private LocalDateTime lastRepairTime;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;
}
