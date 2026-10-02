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
@TableName("rental_order")
public class RentalOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderCode;

    private Long toolId;

    private Long userId;

    private String pickupPerson;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal depositAmount;

    private String depositStatus;

    private String status;

    private LocalDateTime pickupTime;

    private LocalDateTime returnTime;

    private String acceptResult;

    private Long acceptorId;

    private BigDecimal overdueFee;

    private String cancelReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
