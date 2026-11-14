/**
 * 工具实体文件。
 * <p>对应 t_tool 表，是工具资产、审核、租赁与推荐的核心对象。
 * <h2>状态与审核</h2>
 * <p>status 由 ToolStateMachine 约束：AVAILABLE/RENTED/MAINTENANCE/SCRAPPED；
 * auditStatus 独立维护：0 待审核、1 通过、2 需修改。
 * 只有审核通过且未报废的工具对用户可见并可进入向量索引。
 * <h2>并发</h2>
 * <p>version 是 MyBatis-Plus 乐观锁字段，所有状态变更都要求版本匹配；
 * 冲突时调用方返回 409 要求刷新重试。
 * <h2>统计字段</h2>
 * <p>rentalCount 在取件时 +1；lastRepairTime 在恢复可用时刷新，
 * 同时作为蹲守通知的幂等版本。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工具实体，对应数据库表 t_tool。
 * <p>状态由 {@link ToolStateMachine} 约束，所有写入口都要带乐观锁 version；
 * 审核状态与工具状态相互独立：只有审核通过且未报废的工具才对用户可见、可进入向量索引。
 * <p>rentalCount 是累计租赁次数，取件时递增；lastRepairTime 在恢复可用时刷新，
 * 同时用作蹲守通知的幂等版本。
 */
@Data
@TableName("t_tool")
public class Tool {

    @TableId(type = IdType.AUTO)
    // 自增主键，同时用于向量索引 ID 的派生
    private Long id;

    // 业务工具编号，按类别由序列服务生成，展示与检索用
    private String code;

    // 工具名称，用户搜索与向量索引的主要文本
    private String name;

    // 型号规格，可为空；也参与关键字搜索
    private String model;

    /** WX/YY/QJ/QT */
    private String category;

    /** NEW/LIGHT/MEDIUM/SEVERE */
    private String damageLevel;

    /** AVAILABLE/RENTED/MAINTENANCE/SCRAPPED */
    private String status;

    /** 乐观锁版本号，所有状态变更都要求版本匹配 */
    @Version
    // MyBatis-Plus 乐观锁字段；状态流转时版本不匹配即拒绝覆盖
    private Integer version;

    // 押金金额，创建租赁单时快照到租赁单，归还结算以此封顶
    private BigDecimal deposit;

    // 存放区域，管理端可按区域模糊筛选
    private String storageArea;

    // 使用说明，保存前经过 HTML 清洗；可能是 HTML 或 JSON 步骤数组
    private String usageGuide;

    // 工具主图，取 imageUrls 的第一张，便于列表直接展示
    private String imageUrl;

    /** 逗号分隔 */
    private String imageUrls;

    // 采购日期，仅作资产信息展示
    private LocalDate purchaseDate;

    // 供应商，仅作资产信息展示
    private String supplier;

    /** 0 待审核 1 已通过 2 需修改，见 AuditStatus */
    private Integer auditStatus;

    // 审核意见；退回时必填，通过时可选
    private String auditRemark;

    // 最近一次审核的管理端账号 id
    private Long reviewedBy;

    // 最近一次审核时间
    private LocalDateTime reviewedAt;

    // 累计租赁次数，取件时 +1；管理端可据此排序与筛选
    private Integer rentalCount;

    // 最近恢复可用的时间；从未维修时为空，管理端可筛选
    private LocalDateTime lastRepairTime;

    // 创建该工具的管理端账号 id
    private Long createdBy;

    // 创建时间，作为默认排序与稳定次级排序字段
    private LocalDateTime createTime;

    // 最近更新时间
    private LocalDateTime updateTime;
}
