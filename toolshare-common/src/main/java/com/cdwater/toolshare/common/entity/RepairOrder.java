package com.cdwater.toolshare.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;


@Data
@TableName("repair_order")
public class RepairOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String repairCode;

    private Long toolId;

    private Long orderId;

    private Long reporterId;

    private String damagePart;

    private String description;

    private String status;

    private Long handlerId;

    private String estimatedDuration;

    private BigDecimal repairFee;

    private String repairDescription;

    private String acceptResult;

    private String rejectReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
