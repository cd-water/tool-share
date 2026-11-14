package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum RentalStatus implements CodeLabel {

    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    RESERVED("RESERVED", "已预约"),
    PICKED_UP("PICKED_UP", "使用中"),
    RETURNED("RETURNED", "已归还"),
    CANCELLED("CANCELLED", "已取消"),
    OVERDUE("OVERDUE", "已逾期");

    private final String code;
    private final String label;

    RentalStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    public static Optional<RentalStatus> fromCode(String code) {
        return CodeLabel.find(RentalStatus.class, code);
    }
}
