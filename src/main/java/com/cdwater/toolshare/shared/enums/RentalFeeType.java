package com.cdwater.toolshare.shared.enums;

import java.util.Optional;

/** 租赁费用流水类型。WAIVER 的金额表示减免额，用于冲减 OVERDUE/DAMAGE。 */
public enum RentalFeeType implements CodeLabel {

    OVERDUE("OVERDUE", "逾期费"),
    DAMAGE("DAMAGE", "损坏赔偿"),
    WAIVER("WAIVER", "减免"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String label;

    RentalFeeType(String code, String label) {
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

    public static Optional<RentalFeeType> fromCode(String code) {
        return CodeLabel.find(RentalFeeType.class, code);
    }
}
