package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.enums.ToolStatus;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具状态机的唯一合法迁移定义。
 * <p>已报废为终态，不能被任何普通状态更新覆盖。
 */
public final class ToolStateMachine {

    private static final Map<ToolStatus, Set<ToolStatus>> TRANSITIONS = Map.of(
            ToolStatus.AVAILABLE, EnumSet.of(ToolStatus.RENTED, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED),
            ToolStatus.RENTED, EnumSet.of(ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED),
            ToolStatus.MAINTENANCE, EnumSet.of(ToolStatus.AVAILABLE, ToolStatus.SCRAPPED),
            ToolStatus.SCRAPPED, EnumSet.noneOf(ToolStatus.class));

    private ToolStateMachine() {
    }

    public static void assertTransition(ToolStatus from, ToolStatus to) {
        if (from == to) {
            return;
        }
        if (from == ToolStatus.SCRAPPED) {
            throw new BizException(ResultCode.BAD_REQUEST, "已报废的工具不能被恢复");
        }
        Set<ToolStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不允许从「" + from.label() + "」变更为「" + to.label() + "」");
        }
    }
}
