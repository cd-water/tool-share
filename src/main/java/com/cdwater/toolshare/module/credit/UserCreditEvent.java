package com.cdwater.toolshare.module.credit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_credit_event")
public class UserCreditEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** CreditEventType.code() */
    private String eventType;

    /** 实际分数变化，已按 0-100 裁剪 */
    private Integer delta;

    private Long rentalId;

    private Long reviewId;

    private String reason;

    private LocalDateTime createTime;
}
