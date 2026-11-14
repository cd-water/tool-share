/**
 * 租赁单状态机文件。
 * <p>集中定义租赁状态的唯一合法迁移集合。
 * <h2>迁移表</h2>
 * <ul>
 *   <li>PENDING_PAYMENT -> RESERVED / CANCELLED</li>
 *   <li>RESERVED -> PICKED_UP / CANCELLED</li>
 *   <li>PICKED_UP -> OVERDUE / RETURNED</li>
 *   <li>OVERDUE -> RETURNED</li>
 *   <li>RETURNED / CANCELLED -> 无（终态）</li>
 * </ul>
 * <h2>错误语义</h2>
 * <p>相同状态视为幂等通过；非法迁移返回 409，
 * 因为这是并发/业务冲突而不是参数格式错误。
 */
package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 租赁单状态机的唯一合法迁移定义。
 * <p>已归还、已取消为终态；非法跃迁一律 409，不修改数据。
 */
public final class RentalStateMachine {

    // 合法迁移表：
    // 待支付 -> 已预约/已取消；已预约 -> 使用中/已取消；
    // 使用中 -> 逾期/已归还；逾期 -> 已归还；已归还与已取消为终态。
    private static final Map<RentalStatus, Set<RentalStatus>> TRANSITIONS = Map.of(
            RentalStatus.PENDING_PAYMENT, EnumSet.of(RentalStatus.RESERVED, RentalStatus.CANCELLED),
            RentalStatus.RESERVED, EnumSet.of(RentalStatus.PICKED_UP, RentalStatus.CANCELLED),
            RentalStatus.PICKED_UP, EnumSet.of(RentalStatus.OVERDUE, RentalStatus.RETURNED),
            RentalStatus.OVERDUE, EnumSet.of(RentalStatus.RETURNED),
            RentalStatus.RETURNED, EnumSet.noneOf(RentalStatus.class),
            RentalStatus.CANCELLED, EnumSet.noneOf(RentalStatus.class));

    private RentalStateMachine() {
    }

    /**
     * 校验租赁状态迁移是否合法。
     * <p>相同状态视为幂等通过；非法迁移统一返回 409，
     * 因为租赁状态冲突属于并发/业务冲突而不是参数错误。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws BizException 迁移非法时返回 409
     */
    public static void assertTransition(RentalStatus from, RentalStatus to) {
        // 幂等：重复提交同一状态不视为冲突
        if (from == to) {
            return;
        }
        Set<RentalStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BizException(ResultCode.CONFLICT,
                    "不允许从「" + from.label() + "」变更为「" + to.label() + "」");
        }
    }
}
