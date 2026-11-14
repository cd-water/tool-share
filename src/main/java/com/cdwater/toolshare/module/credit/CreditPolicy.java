package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.enums.CreditEventType;

/**
 * 信用分纯规则：初始 100，按事件加减，结果始终裁剪在 0–100。
 * 流水记录裁剪后的实际变化，因此用户分数与流水增量始终一致。
 */
public final class CreditPolicy {

    public static final int INITIAL_SCORE = 100;
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    private CreditPolicy() {
    }

    public static int apply(int current, CreditEventType type) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, current + type.delta()));
    }

    /** 按时归还 +2、逾期 -3、严重损坏 -10、收到 ≤2 星评价 -5。 */
    public static int firstApplication(CreditEventType type) {
        return apply(INITIAL_SCORE, type);
    }
}
