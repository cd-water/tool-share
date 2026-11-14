package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_rental_fee")
public class RentalFee {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rentalId;

    /** OVERDUE/DAMAGE/WAIVER/OTHER */
    private String feeType;

    private BigDecimal amount;

    private String reason;

    private String operatorName;

    private LocalDateTime createTime;
}
