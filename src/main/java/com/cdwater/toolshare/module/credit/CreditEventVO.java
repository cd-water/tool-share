package com.cdwater.toolshare.module.credit;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditEventVO {

    private Long id;

    private String eventType;

    private String eventLabel;

    /** 实际分数变化，已按 0-100 裁剪 */
    private Integer delta;

    private Long rentalId;

    private Long reviewId;

    private String reason;

    private LocalDateTime createTime;
}
