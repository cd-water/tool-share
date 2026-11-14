package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/**
 * 信用分事件及其规则分值。实际入账 delta 可能因 0-100 边界裁剪而不同。
 */
public enum CreditEventType implements CodeLabel {

    ON_TIME_RETURN("ON_TIME_RETURN", "按时归还", 2),
    OVERDUE("OVERDUE", "逾期归还", -3),
    SEVERE_DAMAGE("SEVERE_DAMAGE", "严重损坏", -10),
    LOW_REVIEW("LOW_REVIEW", "低星评价", -5);

    private final String code;
    private final String label;
    private final int delta;

    CreditEventType(String code, String label, int delta) {
        this.code = code;
        this.label = label;
        this.delta = delta;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    public int delta() {
        return delta;
    }

    public static Optional<CreditEventType> fromCode(String code) {
        return CodeLabel.find(CreditEventType.class, code);
    }
}
