package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.enums.CreditEventType;

/**
 * 信用分纯规则：初始 100，按事件加减，结果始终裁剪在 0–100。
 * 流水记录裁剪后的实际变化，因此用户分数与流水增量始终一致。
 *
 * <p>本类不依赖数据库与 Spring 容器，只做纯函数计算，便于针对边界情况
 * 做单元测试。所有入账逻辑（行锁、流水落库、唯一键去重）都在
 * {@link CreditService} 中完成，避免规则与持久化细节耦合。</p>
 */
public final class CreditPolicy {

    /** 新用户初始信用分；分数缺失时也按该值兜底。 */
    public static final int INITIAL_SCORE = 100;

    /** 信用分下界，扣分不会低于该值。 */
    public static final int MIN_SCORE = 0;

    /** 信用分上界，加分不会高于该值。 */
    public static final int MAX_SCORE = 100;

    /**
     * 工具类禁止实例化。
     */
    private CreditPolicy() {
    }

    /**
     * 在给定分数上应用一次事件分值，并裁剪到 0-100 区间。
     *
     * <p>调用方应传入数据库中的当前分数；返回值是事件后的目标分数。
     * 目标分与当前分可能相同（例如满分时继续加分、或零分时继续扣分），
     * 调用方据此决定是否更新用户表。</p>
     *
     * @param current 当前信用分
     * @param type    触发事件的类型，其 {@code delta()} 为规则分值
     * @return 裁剪后的目标分数，范围恒为 0-100
     */
    public static int apply(int current, CreditEventType type) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, current + type.delta()));
    }

    /** 按时归还 +2、逾期 -3、严重损坏 -10、收到 ≤2 星评价 -5。 */
    public static int firstApplication(CreditEventType type) {
        return apply(INITIAL_SCORE, type);
    }
}
