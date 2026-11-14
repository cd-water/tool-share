package com.cdwater.toolshare.module.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具可用性查询结果。
 * <p>available 为 false 时 message 说明原因；
 * freeWindows 是期望窗口内的真实空闲区间，suggestionWindows 是满足原时长的最近可租窗口。
 * 兼容字段 suggestions 为旧前端保留，内容与 suggestionWindows 的格式化文本一致。
 */
@Data
@AllArgsConstructor
public class AvailabilityVO {

    // 期望时段是否完全可租
    private boolean available;

    // 面向用户的结论或不可租原因
    private String message;

    /** 兼容旧前端的文本建议。 */
    private List<String> suggestions;

    /** 期望窗口内的真实空闲区间（已按活动租赁合并）。 */
    // 期望窗口内扣除活动租赁后的空闲区间，按时间升序
    private List<TimeWindow> freeWindows;

    /** 满足期望时长的最近可用窗口。 */
    // 从期望开始时间向后寻找的可用窗口，最多由配置项限制
    private List<TimeWindow> suggestionWindows;

    /**
     * 半开时间区间 [startTime, endTime)，用于占用与空闲窗口表达。
     *
     * @param startTime 区间开始
     * @param endTime   区间结束
     */
    public record TimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
    }
}
