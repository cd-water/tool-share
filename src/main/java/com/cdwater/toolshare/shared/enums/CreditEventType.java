package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 信用分事件及其规则分值。实际入账 delta 可能因 0-100 边界裁剪而不同。
 *
 * <p>每种事件携带一个建议增减值，实际入账 delta 可能因 0-100 边界裁剪
 * 而不同（例如 99 分按时归还只加 1）。流水表记录的是裁剪后的实际变化，
 * 因此用户当前分数始终等于初始分与全部流水 delta 之和。</p>
 */
public enum CreditEventType implements CodeLabel {

    /**
     * 按时归还：正向激励，建议 +2 分。
     */
    ON_TIME_RETURN("ON_TIME_RETURN", "按时归还", 2),
    /**
     * 逾期归还：按次扣分，建议 -3 分。
     */
    OVERDUE("OVERDUE", "逾期归还", -3),
    /**
     * 归还验收为严重损坏：影响较大，建议 -10 分。
     */
    SEVERE_DAMAGE("SEVERE_DAMAGE", "严重损坏", -10),
    /**
     * 收到 2 星及以下评价：反映租用体验差，建议 -5 分。
     */
    LOW_REVIEW("LOW_REVIEW", "低星评价", -5);

    /** 写入信用流水表的英文事件代码。 */
    private final String code;

    /** 用户端展示的中文事件名。 */
    private final String label;

    /** 规则建议的分值变化，实际入账值可能被 0-100 区间裁剪。 */
    private final int delta;

    /**
     * 构造信用事件类型。
     *
     * @param code  英文事件代码
     * @param label 中文事件名
     * @param delta 规则分值变化
     */
    CreditEventType(String code, String label, int delta) {
        this.code = code;
        this.label = label;
        this.delta = delta;
    }

    /**
     * 返回英文事件代码。
     *
     * @return 流水表中持久化的代码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回中文事件名。
     *
     * @return 用于流水展示的名称
     */
    @Override
    public String label() {
        return label;
    }

    /**
     * 返回规则建议的分值变化。
     *
     * @return 正数为加分，负数为扣分
     */
    public int delta() {
        return delta;
    }

    /**
     * 按事件代码反查枚举。
     *
     * @param code 流水表中的英文代码
     * @return 匹配的事件类型；代码为空或未知时返回 {@link Optional#empty()}
     */
    public static Optional<CreditEventType> fromCode(String code) {
        return CodeLabel.find(CreditEventType.class, code);
    }
}
