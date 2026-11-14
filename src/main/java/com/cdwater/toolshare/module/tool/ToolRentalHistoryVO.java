package com.cdwater.toolshare.module.tool;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端工具历史租赁：只暴露已归还租赁的租期窗口与次数，
 * 不包含任何其他居民的姓名、手机号或单号。
 */
@Data
public class ToolRentalHistoryVO {

    private Long toolId;
    private Long completedCount;
    private List<Window> windows;

    @Data
    public static class Window {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
