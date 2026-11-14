package com.cdwater.toolshare.module.dashboard;

import lombok.Data;

/**
 * 管理端看板指标快照。
 *
 * <p>所有字段均为即时聚合值，不落库、不缓存。字段之间的口径需要保持
 * 一致：工具总数包含报废台账，而可借用数还要求审核通过；活跃租赁不包含
 * 待支付与已归还；逾期数按当前时间动态计算。具体统计条件统一维护在
 * {@code DashboardService} 中。</p>
 */
@Data
public class AdminDashboardVO {

    /** 工具总数：包含已报废等全部台账记录。 */
    private long toolTotal;

    /** 可借用工具数：状态可借用且审核已通过。 */
    private long availableToolCount;

    /** 已租出工具数：当前状态为已租出的工具数量。 */
    private long rentedToolCount;

    /** 维修中工具数：当前处于维修状态、暂不可出借的工具数量。 */
    private long maintenanceToolCount;

    /** 活跃租赁数：已预约、使用中与已逾期的订单总数。 */
    private long activeRentalCount;

    /** 逾期租赁数：显式逾期，或使用中但已超过归还时间的订单数。 */
    private long overdueRentalCount;

    /** 待处理报修数：已提交、已核验与维修中的报修单数量。 */
    private long pendingDamageCount;

    /** 平台用户总数，用于运营规模展示。 */
    private long userCount;

    /** 今日新增租赁单数，从服务器当天 0 点开始统计。 */
    private long todayRentalCount;
}
