package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/** 租赁支付流水类型：押金收取、押金退还、超出押金的应收。 */
public enum PaymentRecordType implements CodeLabel {

    DEPOSIT_IN("DEPOSIT_IN", "押金收取"),
    DEPOSIT_REFUND("DEPOSIT_REFUND", "退还押金"),
    RECEIVABLE("RECEIVABLE", "应收");

    private final String code;
    private final String label;

    PaymentRecordType(String code, String label) {
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

    public static Optional<PaymentRecordType> fromCode(String code) {
        return CodeLabel.find(PaymentRecordType.class, code);
    }
}
