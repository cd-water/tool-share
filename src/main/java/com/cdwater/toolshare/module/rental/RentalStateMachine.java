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

    private static final Map<RentalStatus, Set<RentalStatus>> TRANSITIONS = Map.of(
            RentalStatus.PENDING_PAYMENT, EnumSet.of(RentalStatus.RESERVED, RentalStatus.CANCELLED),
            RentalStatus.RESERVED, EnumSet.of(RentalStatus.PICKED_UP, RentalStatus.CANCELLED),
            RentalStatus.PICKED_UP, EnumSet.of(RentalStatus.OVERDUE, RentalStatus.RETURNED),
            RentalStatus.OVERDUE, EnumSet.of(RentalStatus.RETURNED),
            RentalStatus.RETURNED, EnumSet.noneOf(RentalStatus.class),
            RentalStatus.CANCELLED, EnumSet.noneOf(RentalStatus.class));

    private RentalStateMachine() {
    }

    public static void assertTransition(RentalStatus from, RentalStatus to) {
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
