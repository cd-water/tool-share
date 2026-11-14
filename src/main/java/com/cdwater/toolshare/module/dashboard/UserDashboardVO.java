package com.cdwater.toolshare.module.dashboard;

import lombok.Data;

/**
 * 用户端个人看板指标快照。
 *
 * <p>四个数字对应首页快捷入口：可借用工具引导去逛工具，待支付与使用中
 * 引导去订单列表，未读消息引导去消息中心。所有计数均针对当前登录用户，
 * 其中可借用工具数是全平台口径，不区分用户。</p>
 */
@Data
public class UserDashboardVO {

    /** 全平台可借用工具数：审核通过且状态为可借用。 */
    private long availableToolCount;

    /** 当前用户待支付订单数，提醒尽快完成押金支付。 */
    private long pendingPaymentCount;

    /** 当前用户使用中的订单数，包含已逾期订单。 */
    private long rentingCount;

    /** 当前用户未读站内消息数。 */
    private long unreadCount;
}
