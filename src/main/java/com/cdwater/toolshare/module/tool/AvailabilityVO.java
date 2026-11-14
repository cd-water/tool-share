package com.cdwater.toolshare.module.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class AvailabilityVO {

    private boolean available;

    private String message;

    /** 兼容旧前端的文本建议。 */
    private List<String> suggestions;

    /** 期望窗口内的真实空闲区间（已按活动租赁合并）。 */
    private List<TimeWindow> freeWindows;

    /** 满足期望时长的最近可用窗口。 */
    private List<TimeWindow> suggestionWindows;

    public record TimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
    }
}
