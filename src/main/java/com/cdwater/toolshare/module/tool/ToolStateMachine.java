/**
 * 工具状态机文件。
 * <p>集中定义工具状态的唯一合法迁移集合，避免状态判断散落在 Service 中。
 * <h2>迁移表</h2>
 * <ul>
 *   <li>AVAILABLE -> RENTED / MAINTENANCE / SCRAPPED</li>
 *   <li>RENTED -> AVAILABLE / MAINTENANCE / SCRAPPED</li>
 *   <li>MAINTENANCE -> AVAILABLE / SCRAPPED</li>
 *   <li>SCRAPPED -> 无（终态）</li>
 * </ul>
 * <h2>约束</h2>
 * <p>相同状态视为幂等通过；SCRAPPED 不能被普通状态更新覆盖，
 * 恢复只能走 ToolService#restore 的显式入口。
 * 非法迁移统一返回 400。
 */
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

    // 合法迁移表：AVAILABLE <-> RENTED/MAINTENANCE，三者都可转 SCRAPPED；
    // SCRAPPED 为终态，恢复必须走 ToolService.restore 的显式入口。
    private static final Map<ToolStatus, Set<ToolStatus>> TRANSITIONS = Map.of(
            ToolStatus.AVAILABLE, EnumSet.of(ToolStatus.RENTED, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED),
            ToolStatus.RENTED, EnumSet.of(ToolStatus.AVAILABLE, ToolStatus.MAINTENANCE, ToolStatus.SCRAPPED),
            ToolStatus.MAINTENANCE, EnumSet.of(ToolStatus.AVAILABLE, ToolStatus.SCRAPPED),
            ToolStatus.SCRAPPED, EnumSet.noneOf(ToolStatus.class));

    private ToolStateMachine() {
    }

    /**
     * 校验状态迁移是否合法。
     * <p>相同状态视为幂等通过；SCRAPPED 作为终态额外给出明确文案；
     * 其余非法迁移统一返回 400。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws BizException 迁移非法时返回 400
     */
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
