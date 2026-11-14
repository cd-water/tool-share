package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 报修单状态机的唯一合法迁移定义。
 * <p>设计意图：把「哪些状态可以到达哪些状态」收敛到唯一一张表，
 * Service 层只做业务前置校验，不再散落 if/else 判断。新增状态时
 * 必须先在此登记合法迁移，否则任何写操作都会被拒绝。
 * <p>相同状态视为幂等放行，便于重复提交或消息重试时不产生副作用。
 * <p>已完成、已驳回、已报废为终态；非法跃迁一律 409，不修改数据。
 */
public final class DamageStateMachine {

    /**
     * 报修状态迁移表：key 为当前状态，value 为允许到达的下一个状态。
     * <p>COMPLETED、REJECTED、SCRAPPED 为终态，对应空集合。
     */
    private static final Map<ReportStatus, Set<ReportStatus>> TRANSITIONS = Map.of(
            /** 待核验：只能核验通过或驳回。 */
            ReportStatus.SUBMITTED, EnumSet.of(ReportStatus.VERIFIED, ReportStatus.REJECTED),
            /** 已核验：只能进入维修中。 */
            ReportStatus.VERIFIED, EnumSet.of(ReportStatus.REPAIRING),
            /** 维修中：验收后可完成，或工具报废。 */
            ReportStatus.REPAIRING, EnumSet.of(ReportStatus.COMPLETED, ReportStatus.SCRAPPED),
            /** 已完成：终态，不允许再次迁移。 */
            ReportStatus.COMPLETED, EnumSet.noneOf(ReportStatus.class),
            /** 已驳回：终态，不允许再次迁移。 */
            ReportStatus.REJECTED, EnumSet.noneOf(ReportStatus.class),
            /** 已报废：终态，不允许再次迁移。 */
            ReportStatus.SCRAPPED, EnumSet.noneOf(ReportStatus.class));

    /** 工具类禁止实例化。 */
    private DamageStateMachine() {
    }

    /**
     * 校验一次状态迁移是否合法。
     * <p>相同状态视为幂等放行；其余情况严格查表，非法迁移抛出 409 业务异常，
     * 调用方无需再自行判断终态。
     *
     * @param from 当前状态
     * @param to   目标状态
     * <p>异常信息包含来源与目标状态的中文名称，便于前端直接提示用户。
     * @throws BizException 迁移不在白名单内时抛出冲突异常
     */
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
