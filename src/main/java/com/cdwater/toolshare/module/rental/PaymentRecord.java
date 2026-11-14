package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_payment_record")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rentalId;

    /** DEPOSIT_IN/DEPOSIT_REFUND/RECEIVABLE */
    private String recordType;

    private BigDecimal amount;

    private String operatorName;

    private String remark;

    private LocalDateTime createTime;
}
