package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

public enum DepositStatus implements CodeLabel {

    UNPAID("UNPAID", "未支付"),
    PAID("PAID", "已支付"),
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String label;

    DepositStatus(String code, String label) {
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

    public static Optional<DepositStatus> fromCode(String code) {
        return CodeLabel.find(DepositStatus.class, code);
    }
}
