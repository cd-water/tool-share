package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.enums.CreditEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 信用分策略单元测试。
 * <p>
 * 测试范围：初始信用分 100，各类信用事件的分值增减与边界裁剪。
 * 依赖环境：纯策略类，无数据库与 Spring 上下文。
 * 关键不变量：按时归还 +2、逾期 -3、严重损坏 -10、低星评价 -5；
 * 最终分值始终落在 0 到 100 的闭区间内。
 */
class CreditPolicyTest {

    /**
     * 场景：用户初始信用分 100 时分别触发四类信用事件。
     * 前置条件：firstApplication 从满分开始计算。
     * 断言目标：按时 +2、逾期 -3、严重损坏 -10、低星 -5，与产品规则一致。
     */
    @Test
    void first_events_apply_documented_deltas() {
        // 按时归还属于正向行为，信用分从满分基础上加 2 分。
        // 上限裁剪后仍为 100，体现“好行为不突破满分”。
        assertEquals(100, CreditPolicy.firstApplication(CreditEventType.ON_TIME_RETURN));
        // 逾期扣 3 分，是轻微违约的基础惩罚。
        // 与产品规则表保持一致。
        assertEquals(97, CreditPolicy.firstApplication(CreditEventType.OVERDUE));
        // 严重损坏扣 10 分，惩罚力度明显高于逾期。
        // 该分值直接影响后续租赁资格判断。
        assertEquals(90, CreditPolicy.firstApplication(CreditEventType.SEVERE_DAMAGE));
        // 低星评价扣 5 分，位于逾期与严重损坏之间。
        // 四类事件覆盖了信用策略的初始增量。
        assertEquals(95, CreditPolicy.firstApplication(CreditEventType.LOW_REVIEW));
    }

    /**
     * 场景：信用分处于 99 与 100 时再次获得加分。
     * 前置条件：上限固定为 100。
     * 断言目标：结果被裁剪到 100，不出现超过满分的信用值。
     */
    @Test
    void score_never_exceeds_upper_bound() {
        // 99 分加 2 分会被裁剪到 100，
        // 确保信用分不会超过满分。
        assertEquals(100, CreditPolicy.apply(99, CreditEventType.ON_TIME_RETURN));
        // 已满 100 分再加分仍为 100，
        // 避免出现 102 这类越界值影响排序或展示。
        assertEquals(100, CreditPolicy.apply(100, CreditEventType.ON_TIME_RETURN));
    }

    /**
     * 场景：信用分很低时触发较大扣分。
     * 前置条件：下限固定为 0。
     * 断言目标：结果裁剪到 0，避免出现负信用导致后续规则异常。
     */
    @Test
    void score_never_drops_below_zero() {
        // 3 分遭遇严重损坏扣 10 分，结果必须裁剪到 0。
        // 负信用会让后续规则出现非预期比较。
        assertEquals(0, CreditPolicy.apply(3, CreditEventType.SEVERE_DAMAGE));
        // 0 分再逾期仍为 0，
        // 下限同样是闭区间的一部分。
        assertEquals(0, CreditPolicy.apply(0, CreditEventType.OVERDUE));
    }

    /**
     * 场景：分值远离边界时执行扣分。
     * 前置条件：计算结果仍位于 0 到 100 之间。
     * 断言目标：直接应用事件增量，不发生额外裁剪。
     */
    @Test
    void normal_arithmetic_inside_bounds() {
        // 100 分扣 5 分得到 95，处于正常区间内，
        // 不应发生额外裁剪。
        assertEquals(95, CreditPolicy.apply(100, CreditEventType.LOW_REVIEW));
        // 95 分逾期扣 3 分得到 92，
        // 验证普通算术路径与边界路径相互独立。
        assertEquals(92, CreditPolicy.apply(95, CreditEventType.OVERDUE));
    }
}
