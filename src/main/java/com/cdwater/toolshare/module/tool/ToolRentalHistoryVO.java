/**
 * 用户端工具历史租赁视图文件。
 * <p>只暴露已归还租赁的租期窗口与完成次数，
 * 不包含租用人姓名、手机号、单号等他人信息。
 * <h2>隐私约束</h2>
 * <p>任何人查看同一工具都得到相同的历史窗口；
 * 不得在此对象上追加可识别个人的字段。
 * <p>调用方：ToolService#userRentalHistory、UserToolController#history。
 */
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

    // 工具 id
    private Long toolId;
    // 已归还租赁次数
    private Long completedCount;
    // 已归还租赁的租期窗口，按结束时间倒序
    private List<Window> windows;

    @Data
    /**
     * 单个已归还租期窗口。
     */
    public static class Window {
        // 租期开始
        private LocalDateTime startTime;
        // 租期结束
        private LocalDateTime endTime;
    }
}
