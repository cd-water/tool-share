package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 报修单状态机的唯一合法迁移定义。
 * <p>已完成、已驳回、已报废为终态；非法跃迁一律 409，不修改数据。
 */
public final class DamageStateMachine {

    private static final Map<ReportStatus, Set<ReportStatus>> TRANSITIONS = Map.of(
            ReportStatus.SUBMITTED, EnumSet.of(ReportStatus.VERIFIED, ReportStatus.REJECTED),
            ReportStatus.VERIFIED, EnumSet.of(ReportStatus.REPAIRING),
            ReportStatus.REPAIRING, EnumSet.of(ReportStatus.COMPLETED, ReportStatus.SCRAPPED),
            ReportStatus.COMPLETED, EnumSet.noneOf(ReportStatus.class),
            ReportStatus.REJECTED, EnumSet.noneOf(ReportStatus.class),
            ReportStatus.SCRAPPED, EnumSet.noneOf(ReportStatus.class));

    private DamageStateMachine() {
    }

    public static void assertTransition(ReportStatus from, ReportStatus to) {
        if (from == to) {
            return;
        }
        Set<ReportStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BizException(ResultCode.CONFLICT,
                    "报修单不允许从「" + from.label() + "」变更为「" + to.label() + "」");
        }
    }
}
