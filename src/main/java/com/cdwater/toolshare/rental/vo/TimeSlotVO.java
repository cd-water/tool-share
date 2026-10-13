package com.cdwater.toolshare.rental.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可预约时段出参
 */
@Data
public class TimeSlotVO {

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
