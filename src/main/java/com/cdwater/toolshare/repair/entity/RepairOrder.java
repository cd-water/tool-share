package com.cdwater.toolshare.repair.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 损坏报修单表
 */
@Data
@TableName(value = "repair_order", autoResultMap = true)
public class RepairOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报修单号：SQ-BX-YYYYMMDD-XXXX */
    private String repairNo;

    /** 关联租赁单 id */
    private Long rentalOrderId;

    /** 工具 id */
    private Long toolId;

    /** 报修人 user.id */
    private Long reporterId;

    /** 损坏部位 */
    private String damagePart;

    /** 损坏描述（场景、现象） */
    private String damageDesc;

    /** 损坏证明材料 URL 数组（1-3 张，JSON 列） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 核验人 user.id */
    private Long verifyBy;

    /** 核验时间 */
    private LocalDateTime verifyTime;

    /** 核验结果：1确认损坏（需维修） 2疑似损坏（需检测） 3误报 */
    private Integer verifyResult;

    /** 维修负责人（社区维修员/外部师傅，存文本） */
    private String repairerName;

    /** 维修预计时长 */
    private String expectDuration;

    /** 维修开始时间 */
    private LocalDateTime repairStartTime;

    /** 维修进度：1待配件 2维修中 3维修完成 4维修失败（报废） */
    private Integer repairStatus;

    /** 维修费用（元，支付沙箱） */
    private BigDecimal repairFee;

    /** 验收人 user.id */
    private Long acceptBy;

    /** 验收时间 */
    private LocalDateTime acceptTime;

    /** 验收结果：1合格（恢复可用） 2不合格（二次维修） 3部分合格（限用） */
    private Integer acceptResult;

    /** 备注（费用分摊、赔偿说明） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
